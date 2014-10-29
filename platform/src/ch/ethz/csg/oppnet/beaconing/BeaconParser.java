
package ch.ethz.csg.oppnet.beaconing;

import android.content.ContentValues;
import android.util.Log;

import ch.ethz.csg.oppnet.beaconing.BeaconingManager.SocketType;
import ch.ethz.csg.oppnet.data.FullContract.Neighbors;
import ch.ethz.csg.oppnet.data.Identity;
import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.network.NetworkManager;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos;
import ch.ethz.csg.oppnet.utils.ByteUtils;
import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import com.google.protobuf.InvalidProtocolBufferException;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public class BeaconParser extends InterruptibleFailsafeRunnable {
    public static final String TAG = BeaconParser.class.getSimpleName();

    private final BeaconingManager mBM;

    private final BlockingDeque<PossibleBeacon> mBeaconsToProcess = new LinkedBlockingDeque<>();
    private final Set<ByteBuffer> mKnownBeacons = new HashSet<>();
    private final Set<Integer> mProcessedBeacons = new HashSet<>();

    public BeaconParser(BeaconingManager context) {
        super(TAG);
        mBM = context;
    }

    public synchronized void addProcessableBeacon(PossibleBeacon newBeacon) {
        final ByteBuffer wrappedBeaconData = ByteBuffer.wrap(newBeacon.getRawData());

        if (mKnownBeacons.add(wrappedBeaconData)) {
            // It's a new beacon
            mBeaconsToProcess.add(newBeacon);
        }
    }

    public synchronized void clearProcessedBeacons() {
        mProcessedBeacons.clear();
    }

    @Override
    protected void execute() {
        while (!mThread.isInterrupted()) {
            PossibleBeacon nextBeacon;
            try {
                nextBeacon = mBeaconsToProcess.take();
            } catch (InterruptedException e) {
                // Parser got interrupted
                break;
            }

            parseSingleBeacon(nextBeacon);
        }
    }

    private void parseSingleBeacon(PossibleBeacon possibleBeacon) {
        final byte[] rawData = possibleBeacon.getRawData();
        final byte[] origin = possibleBeacon.getOrigin();
        final byte[] ownNodeId = possibleBeacon.getNodeId();

        // Check if it's a beacon, and if we still need to process it
        OppNetProtos.Beacon beacon;
        try {
            beacon = OppNetProtos.Beacon.parseFrom(rawData);
        } catch (InvalidProtocolBufferException e) {
            // This is not a Beacon message
            Log.e(TAG,
                    String.format(
                            "Received a %s packet from %s which is not a beacon!",
                            possibleBeacon.getSocketType().name().toLowerCase(Locale.US),
                            origin),
                    e);
            return;
        }

        if (mProcessedBeacons.contains(beacon.getBeaconId())) {
            // This beacon has already been processed before
            return;
        }

        // It's a meaningful Beacon after all

        final String networkName = possibleBeacon.getNetworkName();
        final long referenceTimestamp = beacon.getTimeCreated();
        mBM.onBeaconParsed(beacon, possibleBeacon, referenceTimestamp);

        // Register the sender as neighbor
        final OppNetProtos.Node sender = beacon.getSender();
        final ContentValues senderValues;
        try {
            senderValues = extractContent(sender, ownNodeId, networkName, referenceTimestamp);
        } catch (EmptyNodeIdException e) {
            Log.w(TAG, "Rejected a beacon with no sender id.");
            return;
        } catch (NodeIsUsException e) {
            // It's a packet from ourself
            Log.w(TAG, "Rejected a beacon from ourself "
                    + "(they should have be filtered out before parsing!).");
            return;
        }

        if (!senderIsOrigin(sender, origin)) {
            // Mismatch, the beacon does not originate from the specified sender
            Log.w(TAG, "(Would have) Rejected a relayed beacon.");
            Log.v(TAG, beacon.toString());
            // TODO: fail again? some old devices don't get IPv6 addresses, but send multicasts
            // return;
        }
        senderValues.put(Neighbors.COLUMN_NETWORK, networkName);
        senderValues.put(Neighbors.COLUMN_TIME_LASTSEEN, beacon.getTimeCreated());

        mBM.mDbController.insertNeighbor(senderValues, sender.getProtocolsList());
        Log.v(TAG, String.format(
                "Received a %s beacon (%s, %s bytes) from node %s",
                possibleBeacon.getSocketType().toString().toLowerCase(Locale.US),
                beacon.getBeaconType().toString().toLowerCase(Locale.US),
                rawData.length,
                ByteUtils.bytesToHex(sender.getNodeId(), Neighbor.BYTES_SHORT_NODE_ID)));

        // Register sender's neighbors
        for (final OppNetProtos.Node neighbor : beacon.getNeighborsList()) {
            final ContentValues otherNeighborValues;
            try {
                otherNeighborValues = extractContent(
                        neighbor, ownNodeId, networkName, referenceTimestamp);
            } catch (EmptyNodeIdException e) {
                Log.w(TAG, "Skipped registering neighbor node with no node id.");
                continue;
            } catch (NodeIsUsException e) {
                // It's us!
                continue;
            }

            mBM.mDbController.insertNeighbor(otherNeighborValues, neighbor.getProtocolsList());
        }

        // Finished processing beacon
        mProcessedBeacons.add(beacon.getBeaconId());
    }

    private boolean senderIsOrigin(OppNetProtos.Node sender, byte[] originAddr) {
        String originType = null;

        switch (originAddr.length) {
            case 4: {
                // IPv4 address
                final byte[] senderIp4 = sender.getIp4Address().toByteArray();
                if (senderIp4.length > 0 && Arrays.equals(senderIp4, originAddr)) {
                    return true;
                }
                originType = "IPv4";
                break;
            }
            case 6: {
                // Bluetooth address
                final byte[] btAddress = sender.getBtAddress().toByteArray();
                if (btAddress.length > 0 && Arrays.equals(btAddress, originAddr)) {
                    return true;
                }
                originType = "Bluetooth";
                break;
            }
            case 16: {
                // IPv6 address
                final byte[] senderIp6 = sender.getIp6Address().toByteArray();
                if (senderIp6.length > 0 && Arrays.equals(senderIp6, originAddr)) {
                    return true;
                }
                originType = "IPv6";
                break;
            }
            default: {
                // Nothing we could use
                Log.w(TAG, String.format(
                        "Unknown origin %s (length: %d)",
                        ByteUtils.bytesToHex(originAddr), originAddr.length));
                return false;
            }
        }

        Log.w(TAG, String.format(
                "Origin %s (%s) is not the packet sender!",
                ByteUtils.bytesToHex(originAddr), originType));
        return false;
    }

    private ContentValues extractContent(OppNetProtos.Node node, byte[] ownNodeId,
            String networkName, long referenceTime)
            throws EmptyNodeIdException, NodeIsUsException {

        final byte[] nodeId = node.getNodeId().toByteArray();
        if (nodeId.length == 0) {
            throw new EmptyNodeIdException();
        } else if (Arrays.equals(ownNodeId, nodeId)) {
            throw new NodeIsUsException();
        }

        final ContentValues values = new ContentValues();
        values.put(Neighbors.COLUMN_IDENTIFIER, nodeId);
        values.put(Neighbors.COLUMN_MULTICAST_CAPABLE, node.getMulticastCapable());

        if (node.hasDeltaLastseen()) {
            values.put(Neighbors.COLUMN_TIME_LASTSEEN, referenceTime - node.getDeltaLastseen());
        }
        if (node.hasIp4Address() || node.hasIp6Address()) {
            final String network = (node.hasNetwork() ? node.getNetwork() : networkName);
            values.put(Neighbors.COLUMN_NETWORK, network);

            if (node.hasIp4Address()) {
                values.put(Neighbors.COLUMN_IP4, node.getIp4Address().toByteArray());
            }
            if (node.hasIp6Address()) {
                values.put(Neighbors.COLUMN_IP6, node.getIp6Address().toByteArray());
            }
        }
        if (node.hasBtAddress()) {
            values.put(Neighbors.COLUMN_BLUETOOTH, node.getBtAddress().toByteArray());
        }

        return values;
    }

    private class EmptyNodeIdException extends Exception {
        private static final long serialVersionUID = 289865661932401267L;

        public EmptyNodeIdException() {
            super();
        }
    }

    private class NodeIsUsException extends Exception {
        private static final long serialVersionUID = -143212430785316974L;

        public NodeIsUsException() {
            super();
        }
    }

    public static final class PossibleBeacon {
        private final byte[] mRawData;
        private final byte[] mOrigin;
        private final long mTimeReceived;
        private final String mReceivingNetworkName;
        private final SocketType mReceivingSocketType;
        private final byte[] mReceiverNodeId;

        public static PossibleBeacon from(DatagramPacket packet, long timeReceived,
                String networkName, SocketType socketType, Identity identity) {

            return new PossibleBeacon(
                    Arrays.copyOf(packet.getData(), packet.getLength()),
                    packet.getAddress().getAddress(),
                    timeReceived,
                    networkName,
                    socketType,
                    identity.getPublicKey());
        }

        public static PossibleBeacon from(byte[] buffer, int length, long timeReceived,
                String btAddress, Identity identity) {

            return new PossibleBeacon(
                    Arrays.copyOf(buffer, length),
                    NetworkManager.parseMacAddress(btAddress),
                    timeReceived,
                    null,
                    SocketType.RFCOMM,
                    identity.getPublicKey());
        }

        public PossibleBeacon(byte[] rawData, byte[] origin, long timeReceived, String networkName,
                SocketType socketType, byte[] receiverNodeId) {
            mRawData = Arrays.copyOf(rawData, rawData.length);
            mOrigin = origin;
            mTimeReceived = timeReceived;
            mReceivingNetworkName = networkName;
            mReceivingSocketType = socketType;
            mReceiverNodeId = receiverNodeId;
        }

        public byte[] getRawData() {
            return mRawData;
        }

        public byte[] getOrigin() {
            return mOrigin;
        }

        public long getTimeReceived() {
            return mTimeReceived;
        }

        public String getNetworkName() {
            return mReceivingNetworkName;
        }

        public SocketType getSocketType() {
            return mReceivingSocketType;
        }

        public byte[] getNodeId() {
            return mReceiverNodeId;
        }
    }
}
