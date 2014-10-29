
package ch.ethz.csg.oppnet.network;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.util.Log;

import com.google.common.base.Objects;
import com.google.common.base.Optional;

import java.util.HashSet;
import java.util.Set;

public final class NetworkManagerState {
    private static final String TAG = NetworkManagerState.class.getSimpleName();

    private final Optional<Boolean> mDataWasEnabled;

    private final boolean mWifiWasEnabled;
    private final int mConnectedWifiId;

    private final boolean mApWasEnabled;
    private final Optional<WifiConfiguration> mApConfiguration;

    private final boolean mBluetoothWasEnabled;

    private final Set<Integer> mTemporaryWifiIds;

    private NetworkManagerState(Optional<Boolean> dataEnabled, boolean wifiEnabled,
            boolean apEnabled, Optional<WifiConfiguration> apConfig,
            boolean btEnabled, int connectedWifiId) {

        mDataWasEnabled = dataEnabled;
        mWifiWasEnabled = wifiEnabled;
        mApWasEnabled = apEnabled;
        mApConfiguration = apConfig;
        mBluetoothWasEnabled = btEnabled;
        mConnectedWifiId = connectedWifiId;

        mTemporaryWifiIds = new HashSet<>();
    }

    public Optional<Boolean> wasDataEnabled() {
        return mDataWasEnabled;
    }

    public boolean wasWifiEnabled() {
        return mWifiWasEnabled;
    }

    public boolean wasApEnabled() {
        return mApWasEnabled;
    }

    public Optional<WifiConfiguration> getApConfig() {
        return mApConfiguration;
    }

    public boolean wasBluetoothEnabled() {
        return mBluetoothWasEnabled;
    }

    public int getConnectedWifiId() {
        return mConnectedWifiId;
    }

    public void addTemporaryWifiId(int id) {
        mTemporaryWifiIds.add(id);
    }

    public Set<Integer> getTemporaryWifiIds() {
        return mTemporaryWifiIds;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("wifi", mWifiWasEnabled)
                .add("to", mConnectedWifiId)
                .add("bt", mBluetoothWasEnabled)
                .add("3g", mDataWasEnabled)
                .toString();
    }

    public static NetworkManagerState captureCurrentState(NetworkManager netManager) {
        final Optional<Boolean> dataEnabled = netManager.isMobileDataEnabled();
        final boolean btEnabled = (
                netManager.isBluetoothEnabling() || netManager.isBluetoothEnabled());

        final boolean wifiEnabled = netManager.isWifiEnabled();
        final boolean apEnabled = netManager.isApEnabled().or(false);
        final Optional<WifiConfiguration> apConfig = netManager.getApConfiguration();
        final int connectedWifiId = netManager.mWifiManager.getConnectionInfo().getNetworkId();

        return new NetworkManagerState(
                dataEnabled, wifiEnabled, apEnabled, apConfig, btEnabled, connectedWifiId);
    }

    public static void restorePreviousState(
            NetworkManager netManager, NetworkManagerState previousState) {
        final int previouslyConnectedNetworkId = previousState.getConnectedWifiId();
        final Set<Integer> temporaryNetworkIds = previousState.getTemporaryWifiIds();
        Log.v(TAG, "Temporarily created " + temporaryNetworkIds.size() + " networks");

        // De-/Reactivate WLAN adapter and clean up configuration
        netManager.setApEnabled(previousState.wasApEnabled());
        netManager.setApConfiguration(previousState.getApConfig());

        final WifiManager wifiManager = netManager.mWifiManager;
        synchronized (wifiManager) {
            if (temporaryNetworkIds.contains(wifiManager.getConnectionInfo().getNetworkId())) {
                // We're still connected to a temporary network
                wifiManager.disconnect();
            }

            // Reset WLAN configuration to previously known networks (remove temporary ones)
            for (final WifiConfiguration config : netManager.getConfiguredWifiNetworks()) {
                final int networkId = config.networkId;

                if (temporaryNetworkIds.contains(networkId)) {
                    wifiManager.disableNetwork(networkId);
                    if (!wifiManager.removeNetwork(networkId)) {
                        Log.w(TAG, "Could not remove temporary network " + config.SSID);
                    }
                } else {
                    wifiManager.enableNetwork(networkId, false);
                }
            }
            // TODO: wifiManager.saveConfiguration(); ???

            // If WiFi was enabled and connected before, let the adapter try to reconnect
            netManager.setWifiEnabled(previousState.wasWifiEnabled());
            if (previouslyConnectedNetworkId >= 0) {
                wifiManager.reconnect();
            }
        }

        // De-/Reactivate mobile data connection
        final Optional<Boolean> mobileDataWasEnabled = previousState.wasDataEnabled();
        if (mobileDataWasEnabled.isPresent()) {
            netManager.setMobileDataEnabled(mobileDataWasEnabled.get());
        }

        // De-/Reactivate bluetooth state
        netManager.setBluetoothEnabled(previousState.wasBluetoothEnabled());
    }
}
