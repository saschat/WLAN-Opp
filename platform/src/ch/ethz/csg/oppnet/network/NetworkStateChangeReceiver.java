
package ch.ethz.csg.oppnet.network;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import ch.ethz.csg.oppnet.utils.TemporaryBroadcastReceiver;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Receiver for system broadcasts related to network connectivity state changes.
 * 
 * @author brunf
 */
public class NetworkStateChangeReceiver extends TemporaryBroadcastReceiver {
    /**
     * Interface to be implemented by network state change listeners.
     */
    public static interface NetworkChangeListener {
        public void onWifiAdapterChanged(boolean enabled);

        public void onWifiNetworkChanged(boolean connected, boolean isFailover);

        public void onBluetoothAdapterChanged(boolean enabled);

        public void onAccessPointModeChanged(boolean activated);
    }

    /**
     * Hidden broadcast action indicating that WiFi AP mode has been enabled, disabled, enabling,
     * disabling, or failed.
     */
    private static final String WIFI_AP_STATE_CHANGED_ACTION =
            "android.net.wifi.WIFI_AP_STATE_CHANGED";

    /**
     * Hidden lookup key for an int that indicates whether Wi-Fi AP is enabled, disabled, enabling,
     * disabling, or failed. Retrieve it with {@link Intent#getIntExtra(String,int)}. <br>
     * States: 0=DISABLING, 1=DISABLED, 2=ENABLING, 3=ENABLED, 4=FAILED
     */
    private static final String EXTRA_WIFI_AP_STATE = "wifi_state";

    /**
     * States of the WiFi adapter when in AP mode. The ordinals correspond to the (hidden) integer
     * constants in the {@link NetManager}.
     */
    private enum ApState {
        DISABLING, DISABLED, ENABLING, ENABLED, FAILED;
    }

    private static final Set<Integer> INTERESTING_WIFI_ADAPTER_STATES = new HashSet<>(3);
    private static final Set<Integer> INTERESTING_BT_ADAPTER_STATES = new HashSet<>(3);
    private static final EnumSet<ApState> INTERESTING_AP_STATES =
            EnumSet.of(ApState.DISABLING, ApState.ENABLED);
    static {
        INTERESTING_WIFI_ADAPTER_STATES.add(WifiManager.WIFI_STATE_DISABLING);
        INTERESTING_WIFI_ADAPTER_STATES.add(WifiManager.WIFI_STATE_ENABLED);

        INTERESTING_BT_ADAPTER_STATES.add(BluetoothAdapter.STATE_TURNING_OFF);
        INTERESTING_BT_ADAPTER_STATES.add(BluetoothAdapter.STATE_ON);
    }

    private final HashSet<NetworkChangeListener> mCallbacks = new HashSet<>();

    private boolean mIsWifiConnected;

    protected boolean hasListeners() {
        return !mCallbacks.isEmpty();
    }

    protected void registerListener(NetworkChangeListener listener) {
        mCallbacks.add(listener);
    }

    protected void unregisterListener(NetworkChangeListener listener) {
        mCallbacks.remove(listener);
    }

    @Override
    protected IntentFilter getIntentFilter() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WIFI_AP_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        return filter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        switch (intent.getAction()) {
            case WIFI_AP_STATE_CHANGED_ACTION: {
                int currentStateInt = intent.getIntExtra(EXTRA_WIFI_AP_STATE, 4);

                final ApState[] states = ApState.values();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                    // The hidden constants were changed with ICS (added 10 to each), so we re-map
                    currentStateInt -= 10;
                }

                final ApState currentState = states[currentStateInt];
                if (INTERESTING_AP_STATES.contains(currentState)) {
                    final boolean isActivated = (currentState == ApState.ENABLED);
                    for (NetworkChangeListener callback : mCallbacks) {
                        callback.onAccessPointModeChanged(isActivated);
                    }
                }
                break;
            }

            case WifiManager.WIFI_STATE_CHANGED_ACTION: {
                // The state of the WiFi adapter has changed
                final int newState = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN);

                if (INTERESTING_WIFI_ADAPTER_STATES.contains(newState)) {
                    final boolean isActivated = (newState == WifiManager.WIFI_STATE_ENABLED);
                    for (NetworkChangeListener callback : mCallbacks) {
                        callback.onWifiAdapterChanged(isActivated);
                    }
                }
                break;
            }

            case BluetoothAdapter.ACTION_STATE_CHANGED: {
                // The state of the Bluetooth adapter has changed
                final int newState = intent.getIntExtra(
                        BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);

                if (INTERESTING_BT_ADAPTER_STATES.contains(newState)) {
                    final boolean isActivated = (newState == BluetoothAdapter.STATE_ON);
                    for (NetworkChangeListener callback : mCallbacks) {
                        callback.onBluetoothAdapterChanged(isActivated);
                    }
                }
                break;
            }

            case ConnectivityManager.CONNECTIVITY_ACTION: {
                // A currently connected network has changed
                final NetworkInfo affectedNetwork =
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                if (affectedNetwork.getType() != ConnectivityManager.TYPE_WIFI) {
                    // We're only interested in WiFi network changes
                    break;
                }

                final boolean noConnectivity = intent.getBooleanExtra(
                        ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
                final boolean isFailover = intent.getBooleanExtra(
                        ConnectivityManager.EXTRA_IS_FAILOVER, false);
                final NetworkInfo failoverNetwork = intent.getParcelableExtra(
                        WifiManager.EXTRA_NETWORK_INFO);

                final boolean stillConnected = (affectedNetwork.isConnected() || !noConnectivity);
                final boolean wifiFailover =
                        isFailover && (failoverNetwork.getType() == ConnectivityManager.TYPE_WIFI);

                if (stillConnected == mIsWifiConnected) {
                    // Connectivity state is still the same.
                    break;
                }

                mIsWifiConnected = stillConnected;
                for (NetworkChangeListener callback : mCallbacks) {
                    callback.onWifiNetworkChanged(stillConnected, wifiFailover);
                }
                break;
            }
        }
    }
}
