
package ch.ethz.csg.oppnet.exchange;

import android.content.Context;
import android.util.Log;

import ch.ethz.csg.oppnet.apps.ProtocolRegistry;
import ch.ethz.csg.oppnet.beaconing.BeaconingManager;
import ch.ethz.csg.oppnet.data.DbController;
import ch.ethz.csg.oppnet.data.FullContract.PacketQueues;
import ch.ethz.csg.oppnet.data.Identity;
import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.lib.data.NeighborObserver;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.TransportPacket;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.TransportPacketOrBuilder;
import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DataExchangeManager implements
        NeighborObserver.NeighborChangeCallback,
        PacketRegistry.PacketAddedCallback {

    private final Context mContext;
    private final BeaconingManager mBeaconingManager;
    private final PacketRegistry mPacketRegistry;
    private final ProtocolRegistry mProtocolRegistry;
    private final NeighborObserver mNeighborObserver;
    private final Identity mIdentity;

    private PacketReceiver mPacketReceiver;

    /**
     * Mapping from neighbor node IDs to Neighbor objects.
     */
    private final Map<ByteBuffer, Neighbor> mNeighborNodeIdMap = new HashMap<>();

    /**
     * Mapping from protocol hash to neighbors which understand this protocol.
     */
    private final Multimap<ByteBuffer, Neighbor> mProtocolNeighborMap = HashMultimap.create();

    public DataExchangeManager(Context context, BeaconingManager beaconingManager) {
        mBeaconingManager = beaconingManager;
        mContext = context.getApplicationContext();
        mPacketRegistry = PacketRegistry.getInstance(mContext);
        mProtocolRegistry = ProtocolRegistry.getInstance(mContext);

        mIdentity = new DbController(context).getMasterIdentity();

        mNeighborObserver = new NeighborObserver(mContext, this);
    }

    public void start() {
        mNeighborObserver.register();
        mPacketRegistry.registerCallback(this);

        mPacketReceiver = new PacketReceiver(mPacketRegistry);
        new Thread(mPacketReceiver).start();
    }

    public void stop() {
        mPacketReceiver.interrupt();

        mPacketRegistry.unregisterCallback(this);
        mNeighborObserver.unregister();
    }

    private void scheduleSendingPackets(Neighbor neighbor) {
        for (Long packetId : mPacketRegistry.getInterestingPacketIds(neighbor)) {
            if (neighbor.hasLastSeenNetwork()) {
                mBeaconingManager.setWifiConnectionLocked(true);
            }
            PacketSenderService.startSendPacket(mContext, neighbor.getRawId(), packetId);
        }
    }

    private void cancelSendingPackets(Neighbor neighbor) {
        // TODO: enable packetsenderservice to cancel sending packets to specific neighbors
    }

    @Override
    public void onNeighborConnected(Neighbor currentNeighbor) {
        // Add neighbor to helper mappings
        mNeighborNodeIdMap.put(ByteBuffer.wrap(currentNeighbor.getNodeId()), currentNeighbor);
        for (ByteBuffer protocol : currentNeighbor.getSupportedProtocols()) {
            mProtocolNeighborMap.put(protocol, currentNeighbor);
        }

        // Check if new neighbor is eligible for packets.
        scheduleSendingPackets(currentNeighbor);
    }

    @Override
    public void onNeighborDisconnected(Neighbor recentNeighbor) {
        // Remove neighbor from helper mappings
        mNeighborNodeIdMap.remove(ByteBuffer.wrap(recentNeighbor.getNodeId()));
        for (ByteBuffer protocol : recentNeighbor.getSupportedProtocols()) {
            mProtocolNeighborMap.remove(protocol, recentNeighbor);
        }

        cancelSendingPackets(recentNeighbor);
    }

    @Override
    public void onNeighborsChanged(Set<Neighbor> currentNeighbors) {
        // Already handled all changed neighbors.
    }

    @Override
    public void onOutgoingPacketAdded(TransportPacketOrBuilder packet, long packetId) {
        if (packet.hasTargetNode()) {
            // Packet is targeted, is the specific node around?
            final Neighbor target = mNeighborNodeIdMap.get(
                    packet.getTargetNode().asReadOnlyByteBuffer());
            if (target != null) {
                // It is!
                PacketSenderService.startSendPacket(mContext, target.getRawId(), packetId);
            }
        } else {
            // Packet is not targeted at some specific node
            for (Neighbor neighbor : mNeighborNodeIdMap.values()) {
                final boolean isUnencryptedPacket =
                        mPacketRegistry.isUnencryptedBroadcastPacket(packetId);
                final boolean isSupportedByNeighbor =
                        mProtocolNeighborMap
                                .get(packet.getProtocol().asReadOnlyByteBuffer())
                                .contains(neighbor);

                if (isUnencryptedPacket || isSupportedByNeighbor) {
                    PacketSenderService.startSendPacket(mContext, neighbor.getRawId(), packetId);
                }
            }
        }
    }

    private class PacketReceiver extends InterruptibleFailsafeRunnable {
        private static final String TAG = "PacketReceiver";

        private final PacketRegistry mPacketRegistry;

        public PacketReceiver(PacketRegistry packetRegistry) {
            super(TAG);
            mPacketRegistry = packetRegistry;
        }

        @Override
        public void execute() {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(PacketSenderService.PACKET_RECEIVING_PORT);
                socket.setSoTimeout(5000);
            } catch (SocketException e) {
                Log.e(TAG, "Could not create socket to receive TransportPackets", e);
                if (socket != null) {
                    socket.close();
                }
                return;
            }

            byte[] buffer = new byte[65536];
            DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
            while (!mThread.isInterrupted()) {
                // Android sometimes limits the incoming packet size to the previously received
                // packet size. The following call circumvents this problem.
                udpPacket.setData(buffer);

                try {
                    socket.receive(udpPacket);
                } catch (SocketTimeoutException e) {
                    // There were no packets in the past few seconds - try again.
                    continue;
                } catch (IOException e) {
                    Log.e(TAG, "Error while receiving TransportPacket:", e);
                    continue;
                }

                final TransportPacket incomingPacket;
                try {
                    incomingPacket = TransportPacket.parseFrom(
                            Arrays.copyOf(udpPacket.getData(), udpPacket.getLength()));
                } catch (InvalidProtocolBufferException e) {
                    // Not a TransportPacket, skip
                    continue;
                }

                // Calculate packet queues to put the incoming packet into
                final List<PacketQueues> queue = new ArrayList<>();

                final boolean isReceiver = (incomingPacket.hasTargetNode() && Arrays.equals(
                        incomingPacket.getTargetNode().toByteArray(), mIdentity.getPublicKey()));

                final ByteBuffer protocol = incomingPacket.getProtocol().asReadOnlyByteBuffer();
                final boolean supportedProtocol =
                        mProtocolRegistry.hasProtocolImplementations(protocol);

                if (isReceiver) {
                    if (!supportedProtocol) {
                        // The packet is targeted at us, but there is no client app installed
                        // which implements the protocol (otherwise, the 'if' clause would have
                        // consumed the packet already) - reject this packet.
                        Log.v(TAG, "Rejecting incoming packet");
                        continue;
                    }

                    Log.v(TAG, "Adding incoming packet (targeted) to INCOMING queue");
                    queue.add(PacketQueues.INCOMING);
                } else {
                    // No target node always means "FORWARDING"
                    Log.v(TAG, "Adding incoming packet to FORWARDING queue");
                    queue.add(PacketQueues.FORWARDING);

                    if (!incomingPacket.hasTargetNode() && supportedProtocol) {
                        Log.v(TAG, "Adding incoming packet (untargeted) to INCOMING queue");
                        queue.add(PacketQueues.INCOMING);
                    }
                }

                mPacketRegistry.registerIncomingPacket(
                        incomingPacket, queue.toArray(new PacketQueues[] {}));
            }

            socket.close();
        }
    }
}
