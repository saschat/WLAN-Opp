
package ch.ethz.csg.oppnet.network;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.MulticastLock;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Build;
import android.util.Log;

import ch.ethz.csg.oppnet.apps.TokenGenerator;
import ch.ethz.csg.oppnet.data.ConfigurationStore;
import ch.ethz.csg.oppnet.network.NetworkStateChangeReceiver.NetworkChangeListener;

import com.google.common.base.Optional;
import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the network, and supplies related utility helpers.
 * 
 * @author brunf
 */
public class NetworkManager {
    public static final String BASE_AP_NAME = "OppNetAP";

    private static final String TAG = NetworkManager.class.getSimpleName();
    private static final String ERROR_MSG_NO_AP_MANIP = "AP manipulation not available.";
    private static final String ERROR_MSG_NO_3G_MANIP = "Mobile Data manipulation not available.";

    private static NetworkManager sInstance;
    private static String sBluetoothAddressString;

    private final Context mContext;
    private final WifiLock mWifiLock;
    private final MulticastLock mMulticastLock;

    protected final ConnectivityManager mConnectivityManager;
    protected final WifiManager mWifiManager;
    protected final BluetoothAdapter mBluetoothAdapter;
    private final NetworkStateChangeReceiver mConnectivityReceiver;

    private final Deque<NetworkManagerState> mPreviousNetworkManagerStates = new ArrayDeque<>();

    private final Optional<Method> mGetMobileDataEnabled;
    private final Optional<Method> mSetMobileDataEnabled;
    private final Optional<Method> mIsWifiApEnabled;
    private final Optional<Method> mSetWifiApEnabled;

    private int mWifiConnectionLockCount;
    private int mBtConnectionLockCount;

    private WifiConfiguration mApConfig;
    private boolean mIsBtEnabling;

    public static synchronized NetworkManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new NetworkManager(context);
            sBluetoothAddressString = ConfigurationStore.getBluetoothAddress(context);
        }
        return sInstance;
    }

    private NetworkManager(Context context) {
        mContext = context;

        // Android system services
        mConnectivityManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        mWifiManager = (WifiManager) mContext.getSystemService(Context.WIFI_SERVICE);
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mConnectivityReceiver = new NetworkStateChangeReceiver();

        // Locks
        mWifiLock = mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, "OppNetWifiLock");
        mWifiLock.setReferenceCounted(false);
        mMulticastLock = mWifiManager.createMulticastLock("OppNetMulticastLock");
        mMulticastLock.setReferenceCounted(false);

        // Discover hidden WLAN AP state methods using reflection
        Class<? extends WifiManager> wmClass = mWifiManager.getClass();
        mIsWifiApEnabled = getAccessibleMethod(wmClass, "isWifiApEnabled");
        mSetWifiApEnabled = getAccessibleMethod(wmClass, "setWifiApEnabled",
                WifiConfiguration.class, Boolean.TYPE);

        if (!mIsWifiApEnabled.isPresent() || !mSetWifiApEnabled.isPresent()) {
            Log.d(TAG, ERROR_MSG_NO_AP_MANIP);
        }

        // Discover hidden 3G state methods using reflection
        Class<? extends ConnectivityManager> cmClass = mConnectivityManager.getClass();
        mGetMobileDataEnabled = getAccessibleMethod(cmClass, "getMobileDataEnabled");
        mSetMobileDataEnabled = getAccessibleMethod(cmClass, "setMobileDataEnabled", Boolean.TYPE);

        if (!mGetMobileDataEnabled.isPresent() || !mSetMobileDataEnabled.isPresent()) {
            Log.d(TAG, ERROR_MSG_NO_3G_MANIP);
        }

        // Prepare basic configuration data for access point mode
        mApConfig = createBaseApConfig(BASE_AP_NAME, WifiConfiguration.KeyMgmt.NONE);
    }

    // NETWORK STATE CHANGE NOTIFICATIONS

    public void registerForConnectivityChanges(NetworkChangeListener callback) {
        if (!mConnectivityReceiver.hasListeners()) {
            mConnectivityReceiver.register(mContext);
        }
        mConnectivityReceiver.registerListener(callback);

        // WiFi connectivity changes are done using sticky broadcasts, so the callback will receive
        // the latest state change directly after registering here. This does not apply to Bluetooth
        // state changes, so we do that manually here.
        callback.onBluetoothAdapterChanged(isBluetoothEnabled());
    }

    public void unregisterForConnectivityChanges(NetworkChangeListener callback) {
        mConnectivityReceiver.unregisterListener(callback);
        if (!mConnectivityReceiver.hasListeners()) {
            mConnectivityReceiver.unregister(mContext);
        }
    }

    // SAVEPOINTS

    public int getSavepointCount() {
        return mPreviousNetworkManagerStates.size();
    }

    /**
     * Captures and stores the current state of the NetManager. Such a savepoint offers an easy way
     * to undo all changes done to the networking configuration on the device. Creating a savepoint
     * should be done before the first call which alters the NetManager's state (i.e., one of the
     * setXYZ methods).
     * 
     * @see NetworkManager#rollback()
     */
    public void createSavepoint() {
        NetworkManagerState currentState = NetworkManagerState.captureCurrentState(this);
        mPreviousNetworkManagerStates.push(currentState);
        Log.v(TAG, String.format("Created savepoint %d: %s",
                mPreviousNetworkManagerStates.size(), currentState));
    }

    /**
     * Restores the networking configuration to a previously captured state. Use this at the end of
     * the NetManager's lifecycle, i.e. before shutting down everything.
     * 
     * @see NetworkManager#createSavepoint()
     */
    public void rollback() {
        if (mPreviousNetworkManagerStates.size() == 0) {
            throw new IllegalStateException(
                    "There is no savepoint to rollback to: create one first.");
        }

        NetworkManagerState.restorePreviousState(this, mPreviousNetworkManagerStates.pop());
        Log.v(TAG, String.format("Rollback to savepoint %d",
                mPreviousNetworkManagerStates.size()));
    }

    // LOCKS

    public void acquireLocks() {
        // Both locks are not reference-counted, so acquiring multiple times is safe: If it is
        // already acquired, nothing happens.
        mWifiLock.acquire();
        mMulticastLock.acquire();
    }

    public void releaseLocks() {
        // Both locks are not reference-counted, so releasing multiple times is safe: The first call
        // to release() will actually release the lock, the others get ignored.
        mWifiLock.release();
        mMulticastLock.release();
    }

    public void resetConnectionLocks() {
        Log.v(TAG, String.format("Resetting connection locks: WiFi %d->0, Bluetooth %d->0",
                mWifiConnectionLockCount, mBtConnectionLockCount));
        mWifiConnectionLockCount = 0;
        mBtConnectionLockCount = 0;
    }

    // WIFI MANAGEMENT

    /**
     * Describes the current state of the WiFi adapter.
     */
    public static enum WifiState {
        /**
         * The WiFi adapter is currently not connected to any network.
         */
        DISCONNECTED,
        /**
         * The WiFi adapter is currently connected to a public network.
         */
        STA_ON_PUBLIC_AP,
        /**
         * The WiFi adapter is currently connected to another OppNet device in access point mode.
         */
        STA_ON_OPPNET_AP,
        /**
         * This device is currently in access point mode, possibly serving other OppNet clients.
         */
        OPPNET_AP;
    }

    public boolean isWifiConnected() {
        final NetworkInfo networkInfo =
                mConnectivityManager.getNetworkInfo(ConnectivityManager.TYPE_WIFI);
        return networkInfo.isConnected();
    }

    /**
     * Returns the current state of the WiFi adapter
     * 
     * @return one of {@link WifiState#DISCONNECTED DISCONNECTED},
     *         {@link WifiState#STA_ON_PUBLIC_AP STA_ON_PUBLIC_AP},
     *         {@link WifiState#STA_ON_OPPNET_AP STA_ON_OPPNET_AP} or {@link WifiState#OPPNET_AP
     *         OPPNET_AP}
     */
    public WifiState getWifiState() {
        WifiState currentState = WifiState.DISCONNECTED;

        if (isApEnabled().or(false)) {
            currentState = WifiState.OPPNET_AP;
        }

        if (isWifiConnected()) {
            final String ssid = unquoteSSID(mWifiManager.getConnectionInfo().getSSID());
            if (ssid.startsWith(BASE_AP_NAME)) {
                currentState = WifiState.STA_ON_OPPNET_AP;
            } else {
                currentState = WifiState.STA_ON_PUBLIC_AP;
            }
        }

        return currentState;
    }

    /**
     * Finds the network interface that is used for WiFi.
     * 
     * @return the WiFi network interface
     */
    public synchronized Optional<NetworkInterface> getWifiNetworkInterface() {
        NetworkInterface wifiInterface = null;

        try {
            final List<NetworkInterface> networkInterfaces =
                    Collections.list(NetworkInterface.getNetworkInterfaces());

            for (NetworkInterface iface : networkInterfaces) {
                final String name = iface.getName();
                if (name.equals("wlan0") || name.equals("eth0") || name.equals("wl0.1")) {
                    wifiInterface = iface;
                    break;
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Error while getting network interfaces", e);
        }

        return Optional.fromNullable(wifiInterface);
    }

    public List<WifiConfiguration> getConfiguredWifiNetworks() {
        final List<WifiConfiguration> networks = mWifiManager.getConfiguredNetworks();
        return (networks == null ? new ArrayList<WifiConfiguration>() : networks);
    }

    /**
     * Generates a list of up to 256 continuous IPv4 addresses in the subnet currently connected to,
     * by incrementing the least significant byte of the IP address from the subnet's start address.
     * 
     * @return a list of InetAddress objects
     */
    public List<InetAddress> getIp4SweepRange() {
        final List<InetAddress> addresses = new ArrayList<>();

        DhcpInfo dhcp = mWifiManager.getDhcpInfo();
        // NOTE: netmask and ipAddress are little-endian, but we want big-endian
        final byte[] netmask = Ints.toByteArray(Integer.reverseBytes(dhcp.netmask));
        final byte[] ownIp = Ints.toByteArray(Integer.reverseBytes(dhcp.ipAddress));

        byte[] baseAddress = {
                (byte) (ownIp[0] & netmask[0]),
                (byte) (ownIp[1] & netmask[1]),
                (byte) (ownIp[2] & netmask[2]),
                (byte) (ownIp[3] & netmask[3])
        };

        // Stop when reaching end of subnet or after at most 255 addresses, whichever comes first
        do {
            ++baseAddress[3];

            if (baseAddress[3] != ownIp[3]) {
                // Skip own address
                try {
                    addresses.add(InetAddress.getByAddress(baseAddress));
                } catch (UnknownHostException e) {
                    // Skip
                }
            }
        } while ((baseAddress[3] | netmask[3]) != (byte) 0xFF);

        return addresses;
    }

    public boolean initiateWifiScan() {
        return mWifiManager.startScan();
    }

    public ScanResults getScanResults() {
        return new ScanResults(mWifiManager.getScanResults(), getConfiguredWifiNetworks());
    }

    // unchecked
    public boolean connectToWifi(ScanResult network) {
        Log.v(TAG, "Requested to connect to network " + network.SSID + " / " + network.BSSID);

        // Check if network is already configured
        int networkId = -1;
        for (WifiConfiguration wifiConfiguration : getConfiguredWifiNetworks()) {
            if (network.SSID.equals(NetworkManager.unquoteSSID(wifiConfiguration.SSID))) {
                if (wifiConfiguration.BSSID == null
                        || wifiConfiguration.BSSID.equals(network.BSSID)) {
                    if (wifiConfiguration.status == WifiConfiguration.Status.CURRENT) {
                        // Already connected to the requested network
                        return true;
                    }
                    networkId = wifiConfiguration.networkId;
                }
            }
        }

        if (networkId < 0) {
            // It's not configured, so add new configuration before connecting
            // NOTE: The assumption here is that the requested network is open.
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = '\"' + network.SSID + '\"';
            wifiConfig.BSSID = network.BSSID;
            wifiConfig.priority = 1;
            wifiConfig.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
            wifiConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
            wifiConfig.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            wifiConfig.status = WifiConfiguration.Status.DISABLED;

            networkId = mWifiManager.addNetwork(wifiConfig);

            final NetworkManagerState savepoint = mPreviousNetworkManagerStates.peek();
            if (savepoint != null) {
                savepoint.addTemporaryWifiId(networkId);
            }
        }

        mWifiManager.disconnect();
        mWifiManager.enableNetwork(networkId, true);
        mWifiManager.reconnect();
        return false;
    }

    /**
     * Checks if the WiFi adapter is enabled.
     * 
     * @return true if adapter is enabled, false otherwise
     */
    public boolean isWifiEnabled() {
        return mWifiManager.isWifiEnabled();
    }

    /**
     * Enables/disables the WiFi adapter.
     * 
     * @return true if operation was successful (or the adapter was already in the requested state)
     */
    public boolean setWifiEnabled(boolean enabled) {
        if (enabled != mWifiManager.isWifiEnabled()) {
            return mWifiManager.setWifiEnabled(enabled);
        }
        return true;
    }

    /**
     * Returns the information about the current WiFi connection.
     * 
     * @return an {@link Optional} containing the current {@link WifiConnection} info if present
     */
    public Optional<WifiConnection> getCurrentConnection() {
        WifiConnection connection = null;

        final WifiState state = getWifiState();
        final Optional<NetworkInterface> iface = getWifiNetworkInterface();
        if (state.equals(WifiState.OPPNET_AP)) {
            connection = WifiConnection.fromApMode(iface.get(), mApConfig.SSID);
        } else if (!state.equals(WifiState.DISCONNECTED)) {
            connection = WifiConnection.fromStaMode(
                    iface.get(), mWifiManager.getDhcpInfo(), mWifiManager.getConnectionInfo());
        }

        return Optional.fromNullable(connection);
    }

    // WIFI AP MANAGEMENT

    /**
     * Checks whether access point mode manipulation methods are available for this device or not.
     * 
     * @return true if access point mode can be manipulated, false otherwise.
     */
    public boolean isApAvailable() {
        return (mIsWifiApEnabled.isPresent() && mSetWifiApEnabled.isPresent());
    }

    /**
     * Checks whether access point mode is activated or not, failing silently if this can not be
     * determined.
     * 
     * @return true if the access point mode is activated, false if it is deactivated, or null upon
     *         failure retrieving this information
     */
    public Optional<Boolean> isApEnabled() {
        Optional<Boolean> isEnabled = Optional.absent();
        if (mIsWifiApEnabled.isPresent()) {
            try {
                isEnabled = Optional.of(
                        (Boolean) mIsWifiApEnabled.get().invoke(mWifiManager));
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Do nothing, fall through
            }
        }
        return isEnabled;
    }

    /**
     * Checks whether access point mode is activated or not, throwing an exception if this can not
     * be determined.
     * 
     * @return true if the access point mode is activated, false if it is deactivated
     * @throws IllegalAccessException if the access point state can not be determined
     */
    public boolean isApEnabledOrThrow() throws IllegalAccessException {
        Optional<Boolean> isEnabled = isApEnabled();
        if (!isEnabled.isPresent()) {
            throw new IllegalAccessException(ERROR_MSG_NO_AP_MANIP);
        }
        return isEnabled.get();
    }

    /**
     * Enables/Disables the access point mode of the WLAN adapter, reporting errors to do so instead
     * of throwing an exception.
     * 
     * @param enabled true to enable the access point mode, false to disable it
     * @return an Optional containing true if the operation was successful, false if it wasn't, or
     *         an absent value if changing the state is not possible on this device
     */
    public Optional<Boolean> setApEnabled(boolean enabled) {
        Optional<Boolean> success = Optional.absent();
        if (mSetWifiApEnabled.isPresent()) {
            final boolean wifiEnabledBefore = isWifiEnabled();
            if (enabled && wifiEnabledBefore) {
                // Deactivate WiFi adapter to allow switching to AP mode
                setWifiEnabled(false);
            }

            try {
                success = Optional.of(
                        (Boolean) mSetWifiApEnabled.get()
                                .invoke(mWifiManager, mApConfig, enabled));
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Restore previous wifi state
                setWifiEnabled(wifiEnabledBefore);
            }
        }
        return success;
    }

    /**
     * Enables/Disables the access point mode of the WLAN adapter, throwing an exception if this is
     * not possible on this device.
     * 
     * @param enabled true to enable the access point mode, false to disable it
     * @return true if operation was successful, false otherwise
     * @throws IllegalAccessException if access point mode can not be activated on this device
     */
    public boolean setApEnabledOrThrow(boolean enabled) throws IllegalAccessException {
        Optional<Boolean> success = setApEnabled(enabled);
        if (!success.isPresent()) {
            throw new IllegalAccessException(ERROR_MSG_NO_AP_MANIP);
        }
        return success.get();
    }

    private WifiConfiguration createBaseApConfig(String baseApName, int keyMgmt) {
        WifiConfiguration baseWifiConfig = new WifiConfiguration();
        baseWifiConfig.SSID = baseApName + "-" + TokenGenerator.generateToken(4);
        baseWifiConfig.allowedKeyManagement.set(keyMgmt);
        return baseWifiConfig;
    }

    // UNUSED
    public void createSecureApConfig(String ssid, String password) {
        if (ssid == null) {
            throw new NullPointerException("SSID must not be null.");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 digits long.");
        }

        mApConfig = createBaseApConfig(ssid, 4); // WifiConfiguration.KeyMgmt.WPA2_PSK is hidden
        mApConfig.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        mApConfig.preSharedKey = password;
    }

    public Optional<WifiConfiguration> getApConfiguration() {
        WifiConfiguration apConfig = null;

        final Optional<Method> getApConfig =
                getAccessibleMethod(mWifiManager.getClass(), "getWifiApConfiguration");

        if (getApConfig.isPresent()) {
            try {
                apConfig = (WifiConfiguration) getApConfig.get().invoke(mWifiManager);
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Do nothing, fall through
            }
        }

        return Optional.fromNullable(apConfig);
    }

    public boolean setApConfiguration(Optional<WifiConfiguration> apConfig) {
        if (apConfig.isPresent()) {
            final Optional<Method> setApConfig = getAccessibleMethod(
                    mWifiManager.getClass(), "setWifiApConfiguration", WifiConfiguration.class);

            if (setApConfig.isPresent()) {
                try {
                    return (Boolean) setApConfig.get().invoke(mWifiManager, apConfig.get());
                } catch (IllegalAccessException
                        | IllegalArgumentException
                        | InvocationTargetException e) {
                    // Do nothing, fall through
                }
            }
        }
        return false;
    }

    // unchecked
    /**
     * Checks whether the device can turn into an access point for tethering.
     * 
     * @return true if the device can act as access point, false otherwise
     */
    public boolean hasApCapabilities() {
        Optional<WifiConfiguration> apConfig = getApConfiguration();
        if (apConfig.isPresent()) {
            if (apConfig.get() != null && apConfig.get().allowedKeyManagement.length() != 0) {
                // A Nexus One that has never used tethering will have length 0
                // Maybe need to find a better way to figure out if config valid
                return true;
            }
        }
        return false;
    }

    // MOBILE DATA (3G) MANIPULATION

    /**
     * Checks whether mobile data is enabled or not, failing silently if this can't be determined.
     * 
     * @return true if mobile data is enabled, false if it is disabled, or null upon failure to
     *         retrieve this information
     */
    public Optional<Boolean> isMobileDataEnabled() {
        Optional<Boolean> isEnabled = Optional.absent();
        if (mGetMobileDataEnabled.isPresent()) {
            try {
                isEnabled = Optional.of(
                        (Boolean) mGetMobileDataEnabled.get().invoke(mConnectivityManager));
            } catch (IllegalArgumentException
                    | InvocationTargetException
                    | IllegalAccessException e) {
                // Do nothing, let it skip through
            }
        }
        return isEnabled;
    }

    /**
     * Checks whether mobile data is enabled or not, and throws an exception if this can't be
     * determined.
     * 
     * @return true if mobile data is enabled, false if it is disabled
     * @throws IllegalAccessException if the mobile data state can not be determined
     */
    public boolean isMobileDataEnabledOrThrow() throws IllegalAccessException {
        Optional<Boolean> isEnabled = isMobileDataEnabled();
        if (!isEnabled.isPresent()) {
            throw new IllegalAccessException(ERROR_MSG_NO_3G_MANIP);
        }
        return isEnabled.get();
    }

    /**
     * Enables/disables mobile data using reflected method of ConnectivityManager, reporting errors
     * instead of throwing exceptions.
     * 
     * @param enabled true to enable mobile data, false to disable
     * @return true if the underlying method was accessible, false otherwise
     */
    private boolean setMobileDataEnabledOrReportFailure(boolean enabled) {
        boolean failed = true;
        if (mSetMobileDataEnabled.isPresent()) {
            try {
                mSetMobileDataEnabled.get().invoke(mConnectivityManager, enabled);
                failed = false;
            } catch (IllegalAccessException
                    | IllegalArgumentException
                    | InvocationTargetException e) {
                // Do nothing, let it skip through
            }
        }
        return failed;
    }

    /**
     * Enables/disables mobile data, and fails silently if setting this is not possible.
     * 
     * @param enabled true to enable mobile data, false to disable
     */
    public void setMobileDataEnabled(boolean enabled) {
        setMobileDataEnabledOrReportFailure(enabled);
    }

    /**
     * Enables/disables mobile data, and throws an exception on failure to do so.
     * 
     * @param enabled true to enable mobile data, false to disable
     * @throws IllegalAccessException if device does not allow mobile data manipulation.
     */
    public void setMobileDataEnabledOrThrow(boolean enabled) throws IllegalAccessException {
        if (setMobileDataEnabledOrReportFailure(enabled)) {
            throw new IllegalAccessException(ERROR_MSG_NO_3G_MANIP);
        }
    }

    // BLUETOOTH

    public boolean isBluetoothAvailable() {
        return (mBluetoothAdapter != null);
    }

    public boolean isBluetoothEnabled() {
        if (mBluetoothAdapter != null) {
            return mBluetoothAdapter.isEnabled();
        }
        return false;
    }

    public boolean isBluetoothEnabling() {
        return mIsBtEnabling;
    }

    public Optional<String> getBluetoothAddress() {
        if (sBluetoothAddressString == null) {
            if (mBluetoothAdapter != null) {
                final String btMac = mBluetoothAdapter.getAddress();
                if (btMac != null) {
                    ConfigurationStore.saveBluetoothAddress(mContext, btMac);
                    sBluetoothAddressString = btMac;
                }
            }
        }
        return Optional.fromNullable(sBluetoothAddressString);
    }

    public Optional<ByteString> getBluetoothAddressAsBytes() {
        ByteString btAddressBytes = null;

        final Optional<String> btAddressString = getBluetoothAddress();
        if (btAddressString.isPresent()) {
            btAddressBytes = ByteString.copyFrom(parseMacAddress(sBluetoothAddressString));
        }

        return Optional.fromNullable(btAddressBytes);
    }

    public boolean setBluetoothEnabled(boolean enable) {
        if (mBluetoothAdapter != null) {
            mIsBtEnabling = enable;

            final boolean isEnabled = mBluetoothAdapter.isEnabled();
            if (enable && !isEnabled) {
                return mBluetoothAdapter.enable();
            } else if (!enable && isEnabled) {
                return mBluetoothAdapter.disable();
            }
            return true;
        }
        return false;
    }

    public boolean doBluetoothScan(boolean start) {
        if (mBluetoothAdapter != null) {
            if (start) {
                return mBluetoothAdapter.startDiscovery();
            } else {
                return mBluetoothAdapter.cancelDiscovery();
            }
        }
        return false;
    }

    public void requestBluetoothDiscoverable() {
        if (mBluetoothAdapter != null) {
            final int scanMode = mBluetoothAdapter.getScanMode();
            if (scanMode != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                final Intent discoverableIntent =
                        new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0);
                discoverableIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                mContext.startActivity(discoverableIntent);
            }
        }
    }

    public BluetoothServerSocket getBluetoothServerSocket(String name, UUID uuid)
            throws IOException {
        BluetoothServerSocket btServerSocket = null;
        if (mBluetoothAdapter != null) {
            btServerSocket =
                    mBluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(name, uuid);
        }
        return btServerSocket;
    }

    public BluetoothDevice getBluetoothDevice(byte[] btAddress) {
        BluetoothDevice btDevice = null;
        if (mBluetoothAdapter != null) {
            final String btAddressString = unparseMacAddress(btAddress);
            if (BluetoothAdapter.checkBluetoothAddress(btAddressString)) {
                btDevice = mBluetoothAdapter.getRemoteDevice(btAddressString);
            }
        }
        return btDevice;
    }

    // HELPERS

    /**
     * Uses reflection to retrieve a method from a class, whose accessible flag is set to true.
     * 
     * @param cls the class on which to search for the method
     * @param method the name of the method to look for
     * @param signature the signature of the method, as needed by the reflection system
     * @return the method, if found on the class, or null otherwise
     */
    private static Optional<Method> getAccessibleMethod(
            Class<?> cls, String method, Class<?>... signature) {
        Method reflectedMethod = null;
        try {
            reflectedMethod = cls.getDeclaredMethod(method, signature);
            reflectedMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "Error while retrieving " + method + " via reflection:", e);
        }
        return Optional.fromNullable(reflectedMethod);
    }

    /**
     * Utility to remove quotation marks around SSID strings as returned by Android.
     * 
     * @param ssid The SSID string to clean.
     * @return the ssid without quotation marks around it
     * @see WifiInfo#getSSID()
     */
    public static String unquoteSSID(String ssid) {
        return ssid.replaceAll("^\"|\"$", "");
    }

    /**
     * Parses a hexadecimal MAC address string to individual bytes.
     * 
     * @param macAddress the ':'-delimited mac address string
     * @return the big-endian mac address byte array
     */
    public static byte[] parseMacAddress(String macAddress) {
        final String[] parts = macAddress.split(":");
        final int len = parts.length;
        assert (len == 6 || len == 8);

        final byte[] parsedBytes = new byte[len];
        for (int i = 0; i < len; i++) {
            final Integer hex = Integer.parseInt(parts[i], 16);
            parsedBytes[i] = hex.byteValue();
        }

        return parsedBytes;
    }

    public static String unparseMacAddress(byte[] macAddress) {
        if (macAddress == null || macAddress.length == 0) {
            return null;
        }

        final StringBuilder addr = new StringBuilder();
        for (byte b : macAddress) {
            if (addr.length() > 0) {
                addr.append(":");
            }
            addr.append(String.format("%02x", b));
        }
        return addr.toString().toUpperCase(Locale.US);
    }

    /**
     * Determines whether a WiFi network is open (unencrypted).
     * 
     * @param network the result of a network scan
     * @return true if the network is open
     */
    public static boolean isOpenNetwork(ScanResult network) {
        if (network.capabilities != null) {
            if (network.capabilities.equals("") || network.capabilities.equals("[ESS]")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Determines whether an SSID belongs to an OppNet access point.
     * 
     * @param ssid the SSID of the network in question
     * @return true if the SSID belongs to an OppNet network
     */
    public static boolean isOppNetSSID(String ssid) {
        return unquoteSSID(ssid).startsWith(BASE_AP_NAME);
    }

    /**
     * Set of Phone Models that do not receive Multicast/Broadcast packets when the screen is off.
     */
    private static final Set<String> sRxMulticastModels = new HashSet<String>();
    static {
        sRxMulticastModels.add("Nexus One");
        sRxMulticastModels.add("HTC Desire");
    }

    public static boolean deviceSupportsMulticastWhenAsleep() {
        return sRxMulticastModels.contains(Build.MODEL);
    }
}
