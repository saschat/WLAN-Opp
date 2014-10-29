
package ch.ethz.csg.oppnet.beaconing;

import android.annotation.SuppressLint;

import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.network.NetworkManager;
import ch.ethz.csg.oppnet.network.NetworkManager.WifiState;
import ch.ethz.csg.oppnet.network.WifiConnection;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.Beacon;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.Beacon.BeaconType;

import com.google.common.base.Optional;
import com.google.protobuf.ByteString;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;

public class BeaconBuilder {
    @SuppressLint("TrulyRandom")
    private static final SecureRandom sRNG = new SecureRandom();

    private final BeaconingManager mBM;
    private final OppNetProtos.Beacon.Builder mBeaconBuilder = OppNetProtos.Beacon.newBuilder();

    public BeaconBuilder(BeaconingManager context) {
        mBM = context;
    }

    /**
     * Builds a beacon without neighbor information, but with "access point likelihood" value.
     * 
     * @param nodeIdentity
     * @param wifiState
     * @param connection
     * @param neighbors
     * @return
     */
    public byte[] buildBeacon(WifiState wifiState, Optional<WifiConnection> connection,
            Set<ByteBuffer> protocols, byte apLikelihood) {

        return makeBeacon(wifiState, connection, protocols, new HashSet<Neighbor>(), apLikelihood)
                .build().toByteArray();
    }

    /**
     * Builds a beacon with neighbor information, but without "access point likelihood" value.
     * 
     * @param nodeIdentity
     * @param wifiState
     * @param connection
     * @param apLikelihood
     * @return
     */
    public byte[] buildBeacon(WifiState wifiState, Optional<WifiConnection> connection,
            Set<ByteBuffer> protocols, Set<Neighbor> neighbors) {

        return makeBeacon(wifiState, connection, protocols, neighbors, null)
                .build().toByteArray();
    }

    public byte[] buildReply(WifiState wifiState, Optional<WifiConnection> connection,
            Set<ByteBuffer> protocols, Set<Neighbor> neighbors, Beacon originalBeacon) {

        return makeBeacon(wifiState, connection, protocols, neighbors, null)
                .setBeaconType(BeaconType.REPLY)
                .build().toByteArray();
    }

    /**
     * Builds a beacon.
     * 
     * @param nodeIdentity
     * @param wifiState
     * @param connection
     * @param neighbors
     * @return
     */
    private Beacon.Builder makeBeacon(WifiState wifiState, Optional<WifiConnection> wifiConnection,
            Set<ByteBuffer> protocols, Set<Neighbor> neighbors, Byte apLikelihood) {
        final long timeCreated = System.currentTimeMillis() / 1000;

        // Build basic data
        mBeaconBuilder.clear()
                .setTimeCreated(timeCreated)
                .setBeaconId(sRNG.nextInt());

        // Build sender information
        final OppNetProtos.Node.Builder senderBuilder =
                mBeaconBuilder.getSenderBuilder()
                        .setNodeId(ByteString.copyFrom(mBM.mMasterIdentity.getPublicKey()));

        if (wifiConnection.isPresent()) {
            WifiConnection connection = wifiConnection.get();
            if (connection.isConnected()) {
                if (connection.hasIp4Address()) {
                    senderBuilder.setIp4Address(
                            ByteString.copyFrom(connection.getIp4Address().get().getAddress()));
                }
                if (connection.hasIp6Address()) {
                    senderBuilder.setIp6Address(
                            ByteString.copyFrom(connection.getIp6Address().get().getAddress()));
                }
            }
        }

        final Optional<ByteString> bluetoothAddress = mBM.mNetManager.getBluetoothAddressAsBytes();
        if (bluetoothAddress.isPresent()) {
            senderBuilder.setBtAddress(bluetoothAddress.get());
        }

        if (!NetworkManager.deviceSupportsMulticastWhenAsleep()) {
            senderBuilder.setMulticastCapable(false);
        }

        if (apLikelihood != null) {
            // If connected to an OppNet AP, send along how "eager" this node is to take over
            // the AP role from the current AP (highest value wins)
            senderBuilder.setApLikelihood(apLikelihood);
        }

        for (final ByteBuffer protocol : protocols) {
            // NOTE: There is a ByteString constructor which takes ByteBuffer's directly, but this
            // somehow does not reliably copy the underlying byte array (it usually can do it once,
            // and then never again). I could not find the cause of that, so using the ByteBuffer's
            // byte array directly is currently the best workaround.
            senderBuilder.addProtocols(ByteString.copyFrom(protocol.array()));
        }

        // Build neighbors
        String currentNetwork = "";
        if (wifiConnection.isPresent() && wifiConnection.get().isConnected()) {
            currentNetwork = wifiConnection.get().getNetworkName().get();
        }

        for (Neighbor neighbor : neighbors) {
            // The following values are directly added to the main beaconBuilder
            final OppNetProtos.Node.Builder neighborBuilder =
                    mBeaconBuilder.addNeighborsBuilder()
                            .setNodeId(ByteString.copyFrom(neighbor.getNodeId()))
                            .setDeltaLastseen((int) (timeCreated - neighbor.getTimeLastSeen()));

            if (!neighbor.isMulticastCapable()) {
                neighborBuilder.setMulticastCapable(false);
            }

            if (neighbor.hasAnyIpAddress()) {
                if (neighbor.hasLastSeenNetwork()
                        && !neighbor.getLastSeenNetwork().equals(currentNetwork)) {
                    neighborBuilder.setNetwork(neighbor.getLastSeenNetwork());
                }
                if (neighbor.hasIp4Address()) {
                    neighborBuilder.setIp4Address(
                            ByteString.copyFrom(neighbor.getIp4Address().getAddress()));
                }
                if (neighbor.hasIp6Address()) {
                    neighborBuilder.setIp6Address(
                            ByteString.copyFrom(neighbor.getIp6Address().getAddress()));
                }
            }

            if (neighbor.hasBluetoothAddress()) {
                neighborBuilder.setBtAddress(
                        ByteString.copyFrom(neighbor.getBluetoothAddress()));
            }
        }

        return mBeaconBuilder;
    }
}
