
package ch.ethz.csg.oppnet.exchange;

import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ch.ethz.csg.oppnet.apps.ProtocolRegistry;
import ch.ethz.csg.oppnet.beaconing.BeaconingManager;
import ch.ethz.csg.oppnet.crypto.CryptoHelper;
import ch.ethz.csg.oppnet.data.DbController;
import ch.ethz.csg.oppnet.data.Implementation;
import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.TransportPacket;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.Set;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in a service on a
 * separate handler thread.
 */
public class PacketSenderService extends IntentService {
    public static final int PACKET_RECEIVING_PORT = 3109;

    private static final String TAG = PacketSenderService.class.getSimpleName();

    private static final String ACTION_SEND_PACKET = "ch.ethz.csg.oppnet.action.SEND_PACKET";

    private static final String EXTRA_NEIGHBOR_ID = "ch.ethz.csg.oppnet.extra.NEIGHBOR_ID";
    private static final String EXTRA_PACKET_ID = "ch.ethz.csg.oppnet.extra.PACKET_ID";

    private DbController mDbController;
    private DatagramSocket mSendSocket;
    private ProtocolRegistry mProtocolRegistry;
    private BeaconingManager mBeaconingManager;

    /**
     * Starts this service to perform action Foo with the given parameters. If the service is
     * already performing a task this action will be queued.
     * 
     * @see IntentService
     */
    public static void startSendPacket(Context context, long neighborId, long packetId) {
        Intent intent = new Intent(context, PacketSenderService.class);
        intent.setAction(ACTION_SEND_PACKET);
        intent.putExtra(EXTRA_NEIGHBOR_ID, neighborId);
        intent.putExtra(EXTRA_PACKET_ID, packetId);
        context.startService(intent);
    }

    public PacketSenderService() throws SocketException {
        super(TAG);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mDbController = new DbController(getApplicationContext());
        try {
            mSendSocket = new DatagramSocket();
        } catch (SocketException e) {
            throw new IllegalStateException("Couldn't create send socket", e);
        }
        mProtocolRegistry = ProtocolRegistry.getInstance(this);
        mBeaconingManager = BeaconingManager.getInstance(this);
    }

    @Override
    public void onDestroy() {
        if (mSendSocket != null) {
            mSendSocket.close();
        }
        mBeaconingManager.resetWifiConnectionLock();
        super.onDestroy();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null || !intent.getAction().equals(ACTION_SEND_PACKET)) {
            // Not our intent.
            return;
        }

        final long neighborId = intent.getLongExtra(EXTRA_NEIGHBOR_ID, -1);
        final long packetId = intent.getLongExtra(EXTRA_PACKET_ID, -1);

        if (neighborId <= 0 || packetId <= 0) {
            Log.e(TAG, "Invalid neighbor/packet ID: " + neighborId + "/" + packetId);
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }

        final TransportPacket.Builder builder;
        try {
            builder = mDbController.getPacket(packetId);
        } catch (IllegalArgumentException e) {
            // Packet does not exist anymore, skip it.
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }
        Neighbor neighbor = mDbController.getNeighbor(neighborId);
        if (!neighbor.hasLastSeenNetwork()) {
            // TODO: Data exchange is currently only supported over Wifi
            Log.v(TAG, "neighbor is only reachable via bluetooth");
            mBeaconingManager.setWifiConnectionLocked(false);
            return;
        }
        Log.v(TAG, "Preparing packet " + packetId + " to be sent to neighbor " + neighbor);

        // Encrypt and/or sign, if necessary
        Set<Implementation> implementations =
                mProtocolRegistry.getProtocolImplementations(builder.getProtocol().toByteArray());

        if (!implementations.isEmpty()) {
            final Implementation impl = implementations.iterator().next();
            if (impl.isEncrypted()) {
                Log.v(TAG, "\tencrypting message...");
                final byte[] ciphertext = CryptoHelper.encrypt(
                        this, builder.getPayload().toByteArray(), neighbor.getNodeId());
                builder.setPayload(ByteString.copyFrom(ciphertext));
            }

            final byte[] nodeId = mDbController.getMasterIdentity().getPublicKey();
            if (impl.isSigned()
                    && Arrays.equals(nodeId, builder.getSourceNode().toByteArray())) {
                // Only change/set the MAC if we're not forwarding an already signed packet
                Log.v(TAG, "\tsigning message...");
                builder.clearMac();
                final byte[] mac = CryptoHelper.sign(this, builder.buildPartial().toByteArray());
                builder.setMac(ByteString.copyFrom(mac));
            }
        }

        // Send packet to neighbor
        final byte[] packet = builder.build().toByteArray();
        Log.v(TAG, "\tsent " + packet.length + " bytes");
        DatagramPacket rawPacket = new DatagramPacket(
                packet, packet.length, neighbor.getAnyIpAddress(), PACKET_RECEIVING_PORT);

        try {
            mSendSocket.send(rawPacket);
        } catch (IOException e) {
            Log.e(TAG, "Error while sending packet " + packetId + " to neighbor " + neighborId, e);
        } finally {
            mBeaconingManager.setWifiConnectionLocked(false);
        }
    }
}
