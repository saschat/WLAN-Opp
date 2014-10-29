
package ch.ethz.csg.oppnet.beaconing;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import ch.ethz.csg.oppnet.apps.ProtocolRegistry;
import ch.ethz.csg.oppnet.beaconing.BeaconParser.PossibleBeacon;
import ch.ethz.csg.oppnet.beaconing.UdpReceiver.UdpMulticastReceiver;
import ch.ethz.csg.oppnet.beaconing.UdpReceiver.UdpUnicastReceiver;
import ch.ethz.csg.oppnet.core.Policy;
import ch.ethz.csg.oppnet.core.Policy.BeaconingInterval;
import ch.ethz.csg.oppnet.core.Policy.Feature;
import ch.ethz.csg.oppnet.data.DbController;
import ch.ethz.csg.oppnet.data.FullContract;
import ch.ethz.csg.oppnet.data.Identity;
import ch.ethz.csg.oppnet.exchange.DataExchangeManager;
import ch.ethz.csg.oppnet.network.NetworkManager;
import ch.ethz.csg.oppnet.network.NetworkManager.WifiState;
import ch.ethz.csg.oppnet.network.NetworkStateChangeReceiver.NetworkChangeListener;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.Beacon.BeaconType;
import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import com.google.common.net.InetAddresses;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class BeaconingManager implements NetworkChangeListener {
    /**
     * Broadcast action: Sent after a beaconing period has completed successfully (i.e., has not
     * been aborted). Carries the beaconing ID (which was used to activate the beaconing in the
     * first place) as an extra.
     * 
     * @see #EXTRA_BEACONING_ID
     * @see #setActive()
     */
    public static final String ACTION_BEACONING_FINISHED =
            "ch.ethz.csg.oppnet.action.BEACONING_FINISHED";
    /**
     * Lookup key for the beaconing ID which just finished.
     * 
     * @see Intent#getIntExtra(String, int)
     */
    public static final String EXTRA_BEACONING_ID = "beaconing_id";

    protected static final int RECEIVER_PORT_UNICAST = 3108;
    protected static final int RECEIVER_PORT_MULTICAST = 5353;
    protected static final InetAddress[] MULTICAST_GROUPS = {
            InetAddresses.forString("224.0.0.251"), InetAddresses.forString("ff02::fb")
    };

    protected static final int RECEIVER_SOCKET_TIMEOUT = 5 * 1000; // 5 seconds
    protected static final int RECEIVER_BUFFER_SIZE = 4 * 1024; // 4 KiB

    protected static final String SDP_NAME = "OppNetBeaconingManager";
    protected static final UUID OPP_NET_UUID =
            UUID.fromString("35b0a0a8-c92a-4c63-b7d8-d0a55ca18159");

    protected static enum SocketType {
        UNICAST, MULTICAST, RFCOMM;
    };

    private static final String TAG = BeaconingManager.class.getSimpleName();
    private static BeaconingManager sInstance;

    protected final Context mContext;
    protected final PowerManager mPowerManager;
    protected final NetworkManager mNetManager;
    protected final DbController mDbController;
    protected final ProtocolRegistry mProtocolRegistry;
    protected final Identity mMasterIdentity;
    protected final BeaconBuilder mBeaconBuilder;
    protected final DataExchangeManager mDataExchangeManager;

    protected ScheduledExecutorService mThreadPool;
    protected BeaconParser mBeaconParser;
    protected InterruptibleFailsafeRunnable mBeaconingInterval;

    protected UdpReceiver mUnicastReceiver;
    protected UdpReceiver mMulticastReceiver;
    protected WeakReference<UdpSender> mOneTimeWifiSender;
    protected ScheduledFuture<?> mRegularWifiSender;
    protected RfcommReceiver mBluetoothReceiver;
    protected WeakReference<RfcommSender> mBtSender;
    protected final Map<String, BluetoothSocket> mBluetoothSockets = new HashMap<>();

    private int mWifiConnectionLockCount;
    private int mBtConnectionLockCount;

    protected volatile BeaconingState mState = BeaconingState.STOPPED;
    protected volatile Policy mPolicy;
    private BroadcastReceiver mPolicyChangedReceiver;

    private int mCurrentBeaconingRoundId;
    private int mCurrentApLikelihood;
    protected boolean mIsDesignatedAp;

    // LIFECYCLE

    public static synchronized BeaconingManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BeaconingManager(context);
        }
        return sInstance;
    }

    private BeaconingManager(Context context) {
        mContext = context;

        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        mNetManager = NetworkManager.getInstance(mContext);
        mDbController = new DbController(mContext);
        mProtocolRegistry = ProtocolRegistry.getInstance(mContext);
        mMasterIdentity = mDbController.getMasterIdentity();
        mBeaconBuilder = new BeaconBuilder(this);
        mDataExchangeManager = new DataExchangeManager(mContext, this);
    }

    // BEACONING STATE

    private synchronized void doStateTransition(final BeaconingState newState) {
        if (newState.equals(mState)) {
            // The beaconing manager is already in the target state.
            return;
        } else if (!newState.isPossibleOrigin(mState)) {
            // The transition is not possible
            throw new IllegalStateException(
                    "Can not transition from " + mState + " to " + newState);
        }

        // Perform state transition and execute main body of new state
        Log.v(TAG, "Transitioning from " + mState + " to " + newState);
        mState.onLeave(this);
        mState = newState;
        newState.onEnter(this);
        newState.execute(this);

        notifyNeighborUris(mContext);
    }

    private void setupThreadPool() {
        if (mThreadPool == null) {
            mThreadPool = Executors.newScheduledThreadPool(10);
        }
    }

    private void teardownThreadPool() {
        stopBeaconSenders();
        stopBeaconReceivers();
        if (mThreadPool != null) {
            mThreadPool.shutdownNow();
            mThreadPool = null;
        }
        Log.v(TAG, "Thread pool shut down");
    }

    private void setupPolicyReceiver() {
        if (mPolicyChangedReceiver == null) {
            mPolicyChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    mPolicy = (Policy) intent.getSerializableExtra(Policy.EXTRA_NEW_POLICY);
                    sCurrentBeaconingInterval = mPolicy.getBeaconingInterval();
                }
            };
            Policy.registerPolicyChangedReceiver(mContext, mPolicyChangedReceiver);
            mPolicy = Policy.getCurrentPolicy(mContext);
        }
    }

    private void teardownPolicyReceiver() {
        if (mPolicyChangedReceiver != null) {
            Policy.unregisterPolicyChangedReceiver(mContext, mPolicyChangedReceiver);
            mPolicyChangedReceiver = null;
            mPolicy = null;
        }
    }

    // BEACON RECEIVERS

    private void activateBeaconReceivers() {
        mNetManager.createSavepoint();
        mNetManager.acquireLocks();

        mBeaconParser = new BeaconParser(this);
        mThreadPool.execute(mBeaconParser);

        // Upon a successful connection, the change receiver will start the wifi beacon receivers
        // (it's a sticky broadcast). However, that does not apply to bluetooth, so we need to take
        // care of them manually.
        mNetManager.registerForConnectivityChanges(this);
        if (mNetManager.isBluetoothEnabled()) {
            startBluetoothReceiver();
        }

        mDataExchangeManager.start();
    }

    private void deactivateBeaconReceivers() {
        mDataExchangeManager.stop();
        mNetManager.unregisterForConnectivityChanges(this);
        stopBeaconReceivers();
        mBeaconParser.interrupt();
        mNetManager.releaseLocks();
        mNetManager.rollback();
    }

    protected void startWifiReceiver() {
        try {
            if (mUnicastReceiver == null) {
                mUnicastReceiver = new UdpUnicastReceiver(this);
                mThreadPool.execute(mUnicastReceiver);
            }

            if (mNetManager.getWifiState().equals(WifiState.STA_ON_PUBLIC_AP)) {
                // Multicast is only supported on public networks
                if (mMulticastReceiver == null) {
                    mMulticastReceiver = new UdpMulticastReceiver(this);
                    mThreadPool.execute(mMulticastReceiver);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while creating WiFi receivers:", e);
        }
    }

    protected void stopWifiReceiver() {
        if (mUnicastReceiver != null) {
            mUnicastReceiver.interrupt();
            mUnicastReceiver = null;
        }
        if (mMulticastReceiver != null) {
            mMulticastReceiver.interrupt();
            mMulticastReceiver = null;
        }
    }

    protected void startBluetoothReceiver() {
        try {
            if (mBluetoothReceiver == null) {
                mBluetoothReceiver = new RfcommReceiver(this);
                mThreadPool.execute(mBluetoothReceiver);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while creating Bluetooth receiver, aborting.", e);
        }
    }

    protected void stopBluetoothReceiver() {
        if (mBluetoothReceiver != null) {
            mBluetoothReceiver.interrupt();
            mBluetoothReceiver = null;
        }
        interruptBluetoothConnections();
    }

    private void interruptBluetoothConnections() {
        for (BluetoothSocket btSocket : mBluetoothSockets.values()) {
            try {
                btSocket.close();
            } catch (IOException e) {
                Log.e(TAG, String.format("Error while closing bluetooth socket to %s:",
                        btSocket.getRemoteDevice().getAddress()), e);
            }
        }
        mBluetoothSockets.clear();
    }

    protected void stopBeaconReceivers() {
        stopWifiReceiver();
        stopBluetoothReceiver();
    }

    // BEACON SENDERS

    public void sendSingleBeacon() {
        if (mNetManager.isWifiConnected()) {
            startWifiSender(false);
        }
        if (mNetManager.isBluetoothEnabled()) {
            startBluetoothSender(0);
        }
    }

    protected void startWifiSender(boolean repeating) {
        if (mRegularWifiSender != null && !mRegularWifiSender.isDone()) {
            // The senders are already running
            Log.v(TAG, "Wifi senders already running");
            return;
        }

        mCurrentApLikelihood = 0;
        if (mNetManager.getWifiState().equals(WifiState.OPPNET_AP)
                && mPolicy.allows(Feature.WIFI_AP)) {
            // Signal willingness to take over AP mode
            mCurrentApLikelihood = (int) (Math.random() * 256);
        }
        mIsDesignatedAp = (mCurrentApLikelihood > 0);

        UdpSender oneTimeWifiSender = new UdpSender(this, true, 1, mCurrentApLikelihood);
        mOneTimeWifiSender = new WeakReference<UdpSender>(oneTimeWifiSender);
        mThreadPool.execute(oneTimeWifiSender);

        if (repeating) {
            UdpSender repeatingWifiSender = new UdpSender(this, false, 3, mCurrentApLikelihood);
            mRegularWifiSender =
                    mThreadPool.scheduleAtFixedRate(repeatingWifiSender, 2, 3, TimeUnit.SECONDS);
        }
    }

    protected void stopWifiSender() {
        if (mOneTimeWifiSender != null) {
            UdpSender oneTimeSender = mOneTimeWifiSender.get();
            if (oneTimeSender != null) {
                oneTimeSender.interrupt();
                mOneTimeWifiSender = null;
            }
        }

        if (mRegularWifiSender != null) {
            mRegularWifiSender.cancel(true);
            mRegularWifiSender = null;
        }
    }

    private void startBluetoothSender(int delay) {
        if (mBtSender == null || mBtSender.get() == null) {
            RfcommSender btSender = new RfcommSender(this);
            mBtSender = new WeakReference<RfcommSender>(btSender);
            mThreadPool.schedule(btSender, delay, TimeUnit.SECONDS);
        }
    }

    private void stopBluetoothSender() {
        if (mBtSender != null) {
            RfcommSender btSender = mBtSender.get();
            if (btSender != null) {
                btSender.interrupt();
                mBtSender = null;
            }
        }
    }

    protected void stopBeaconSenders() {
        stopWifiSender();
        stopBluetoothSender();
    }

    // BEACONING INTERVAL

    private void startBeaconingInterval() {
        if (mBeaconingInterval == null) {
            mBeaconParser.clearProcessedBeacons();
            mBeaconingInterval = new BeaconingIntervalHandler(this, mCurrentBeaconingRoundId);
            new Thread(mBeaconingInterval).start();
        }
    }

    private void stopBeaconingInterval() {
        if (mBeaconingInterval != null) {
            mBeaconingInterval.interrupt();
            mBeaconingInterval = null;

            mNetManager.registerForConnectivityChanges(this);
        }
    }

    // CALLBACKS

    protected void onBeaconReceived(PossibleBeacon possibleBeacon) {
        mBeaconParser.addProcessableBeacon(possibleBeacon);
    }

    protected void onBeaconParsed(
            OppNetProtos.Beacon beacon, PossibleBeacon rawData, long timeReceived) {

        if (mState == BeaconingState.PASSIVE && beacon.getBeaconType() == BeaconType.ORIGINAL) {
            final byte[] origin = rawData.getOrigin();
            if (origin.length != 6) {
                // Reply to IPv4/IPv6 beacons (bluetooth beacons are always answered directly)
                try {
                    final InetAddress replyTo = InetAddress.getByAddress(origin);
                    UdpSender replySender =
                            new UdpSender(this, replyTo, beacon, mCurrentApLikelihood);
                    mThreadPool.execute(replySender);
                } catch (UnknownHostException e) {
                    // should never happen
                }
            }
        }

        if (mNetManager.getWifiState().equals(WifiState.STA_ON_OPPNET_AP)) {
            final int remoteApLikelihood = beacon.getSender().getApLikelihood() & 0xFF;
            mIsDesignatedAp = mIsDesignatedAp
                    && (mCurrentApLikelihood > 0)
                    && (mCurrentApLikelihood >= remoteApLikelihood);
        }

        notifyNeighborUris(mContext);
    }

    protected void onBtDeviceDisconnected(BluetoothDevice btDevice) {
        mBluetoothSockets.remove(btDevice.getAddress());
    }

    public void onBeaconingIntervalFinished(int beaconingId) {
        doStateTransition(BeaconingState.PASSIVE);

        final Intent finishedIntent = new Intent(ACTION_BEACONING_FINISHED);
        finishedIntent.putExtra(EXTRA_BEACONING_ID, beaconingId);
        LocalBroadcastManager.getInstance(mContext).sendBroadcast(finishedIntent);
    }

    protected boolean connectToBtDevice(BluetoothDevice btDevice) {
        if (mBluetoothSockets.containsKey(btDevice.getAddress())) {
            // Target device is already connected
            return true;
        }

        try {
            BluetoothSocket btSocket =
                    btDevice.createInsecureRfcommSocketToServiceRecord(OPP_NET_UUID);
            RfcommConnection btConnection = new RfcommConnection(this, btSocket, true);
            mBluetoothSockets.put(btDevice.getAddress(), btSocket);
            mThreadPool.execute(btConnection);
        } catch (IOException e) {
            Log.e(TAG, "Error while connecting to bluetooth device " + btDevice.getName());
            return false;
        }
        return true;
    }

    // CONNECTION LOCKS

    public synchronized void resetWifiConnectionLock() {
        mWifiConnectionLockCount = 0;
        rollbackNetworkStateIfSuitable();
    }

    public synchronized void setWifiConnectionLocked(boolean locked) {
        final int modifier = (locked ? 1 : -1);
        mWifiConnectionLockCount = Math.max(0, mWifiConnectionLockCount + modifier);
        rollbackNetworkStateIfSuitable();
    }

    public boolean isWifiConnectionLocked() {
        return mWifiConnectionLockCount > 0;
    }

    public synchronized void resetBtConnectionLock() {
        mBtConnectionLockCount = 0;
        rollbackNetworkStateIfSuitable();
    }

    public synchronized void setBtConnectionLocked(boolean locked) {
        final int modifier = (locked ? 1 : -1);
        mBtConnectionLockCount = Math.max(0, mBtConnectionLockCount + modifier);
        rollbackNetworkStateIfSuitable();
    }

    public boolean isBtConnectionLocked() {
        return mBtConnectionLockCount > 0;
    }

    private void rollbackNetworkStateIfSuitable() {
        if (mWifiConnectionLockCount + mBtConnectionLockCount == 0) {
            // Locks are now completely released
            final int savepointCount = mNetManager.getSavepointCount();
            if ((mState == BeaconingState.PASSIVE && savepointCount > 1)
                    || (mState == BeaconingState.STOPPED && savepointCount > 0)) {
                mNetManager.rollback();
            }
        }
    }

    // NETWORK CHANGE LISTENERS

    @Override
    public void onWifiAdapterChanged(boolean enabled) {
        // TODO
    }

    @Override
    public void onWifiNetworkChanged(boolean connected, boolean isFailover) {
        if (mState == BeaconingState.PASSIVE) {
            if (connected) {
                startWifiReceiver();
                startWifiSender(false);
            } else {
                stopWifiSender();
                stopWifiReceiver();
            }
        }
    }

    @Override
    public void onBluetoothAdapterChanged(boolean enabled) {
        if (mState == BeaconingState.PASSIVE) {
            if (enabled) {
                startBluetoothReceiver();
                startBluetoothSender(15);
            } else {
                stopBluetoothSender();
                stopBluetoothReceiver();
            }
        }
    }

    @Override
    public void onAccessPointModeChanged(boolean activated) {
        // We don't handle this here.
    }

    // EXTERNAL API
    public boolean isActivated() {
        return (mState != BeaconingState.STOPPED);
    }

    public void setStopped() {
        doStateTransition(BeaconingState.STOPPED);
    }

    public void setPassive() {
        doStateTransition(BeaconingState.PASSIVE);
    }

    public void setActive(int beaconingRoundId) {
        mCurrentBeaconingRoundId = beaconingRoundId;
        doStateTransition(BeaconingState.ACTIVE);
    }

    /**
     * TODO
     * 
     * @author fubinho
     */
    public static enum BeaconingState {
        /**
         * The beaconing manager is stopped (not instantiated).
         */
        STOPPED {
            @Override
            public void onEnter(BeaconingManager bm) {
                bm.deactivateBeaconReceivers();
                bm.teardownPolicyReceiver();
                bm.teardownThreadPool();
            }

            @Override
            public void onLeave(BeaconingManager bm) {
                bm.setupThreadPool();
                bm.setupPolicyReceiver();
                bm.activateBeaconReceivers();
            }

            @Override
            public boolean isPossibleOrigin(BeaconingState state) {
                return state.equals(PASSIVE);
            }
        },
        /**
         * The beaconing manager is started, but does not regularly send beacons on its own. It only
         * responds to other neighbors beacons, or sends a "beacon burst" if a network change has
         * been detected.
         */
        PASSIVE {
        },
        /**
         * The beaconing manager is actively scanning for neighbors. If the current {@link Policy}
         * allows it, this state also switches between different network modes.
         */
        ACTIVE {
            @Override
            public void onEnter(BeaconingManager bm) {
                bm.startBeaconingInterval();
            }

            @Override
            public void onLeave(BeaconingManager bm) {
                bm.stopBeaconingInterval();
            }

            @Override
            public boolean isPossibleOrigin(BeaconingState state) {
                return state.equals(PASSIVE);
            }
        };

        /**
         * Executes when entering this state. By default, this method does nothing.
         * 
         * @param bm
         */
        public void onEnter(BeaconingManager bm) {
        }

        /**
         * Executes the main function of this state. By default, this method does nothing.
         * 
         * @param bm
         */
        public void execute(BeaconingManager bm) {
        }

        /**
         * Executes when leaving this state. By default, this method does nothing.
         * 
         * @param bm
         */
        public void onLeave(BeaconingManager bm) {
        }

        /**
         * Checks if this state can be transitioned to from the specified state. By default, this
         * method returns {@code true}, except the specified state is the same as this one.
         * 
         * @param state the state from which the transition would occur
         * @return true if the transition is possible, false otherwise
         */
        public boolean isPossibleOrigin(BeaconingState state) {
            return !state.equals(this);
        }
    }

    // CONTENT NOTIFIER
    
    private static BeaconingInterval sCurrentBeaconingInterval = BeaconingInterval.OFF;

    public static long getCurrentTimestamp() {
        final int periodMillis = Math.max(
                2 * sCurrentBeaconingInterval.getIntervalMillis(), 20 * 60 * 1000);

        return (System.currentTimeMillis() - periodMillis) / 1000;
    }

    public static long getRecentTimestamp() {
        return (System.currentTimeMillis() / 1000) - (24 * 60 * 60); // last 24 hours
    }

    private void notifyNeighborUris(Context context) {
        final Uri[] neighborUris = new Uri[] {
                FullContract.Neighbors.URI_ALL,
                FullContract.Neighbors.URI_CURRENT,
                FullContract.Neighbors.URI_RECENT,
                FullContract.NeighborProtocols.URI_ALL,
                FullContract.NeighborProtocols.URI_CURRENT,
                FullContract.NeighborProtocols.URI_RECENT
        };

        ContentResolver resolver = context.getContentResolver();
        for (Uri uri : neighborUris) {
            resolver.notifyChange(uri, null);
        }
    }
}
