
package ch.ethz.csg.oppnet.core;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import ch.ethz.csg.oppnet.beaconing.BeaconingIntervalHandler;
import ch.ethz.csg.oppnet.beaconing.BeaconingManager;
import ch.ethz.csg.oppnet.core.Policy.BeaconingInterval;
import ch.ethz.csg.oppnet.core.Policy.Feature;
import ch.ethz.csg.oppnet.data.ConfigurationStore;
import ch.ethz.csg.oppnet.network.NetworkManager;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

public class SupervisorService extends Service {
    public static final String ACTION_START = "ch.ethz.csg.oppnet.action.START_SUPERVISOR";
    public static final String ACTION_WAKE_UP = "ch.ethz.csg.oppnet.action.WAKE_UP_SUPERVISOR";

    private static final String TAG = SupervisorService.class.getSimpleName();

    public static final SupervisorState DEFAULT_STATE = SupervisorState.IDLE;
    private static final Set<SupervisorState> ACTIVE_STATES = EnumSet.of(
            SupervisorState.RUNNING,
            SupervisorState.SLEEPING);

    private HandlerThread mHandlerThread;
    private Handler mStateHandler;
    private volatile SupervisorState mState = SupervisorState.STOPPED;

    private volatile Policy mPolicy;
    private BroadcastReceiver mPolicyChangedReceiver;
    private Set<Callback> mCallbacks = new HashSet<>();

    private BroadcastReceiver mBeaconingFinishedReceiver;
    private int mBeaconingRoundId;

    private PowerManager mPowerManager;
    private PowerManager.WakeLock mWakeLock;
    private AlarmManager mAlarmManager;
    private PendingIntent mWakeUpIntent;

    private NetworkManager mNetManager;
    private BeaconingManager mBeaconingManager;

    @Override
    public void onCreate() {
        mHandlerThread = new HandlerThread(TAG);
        mHandlerThread.start();
        mStateHandler = new Handler(mHandlerThread.getLooper());

        mNetManager = NetworkManager.getInstance(this);
        mBeaconingManager = BeaconingManager.getInstance(this);

        mAlarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mWakeLock = mPowerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "OppNetSupervisorWakeLock");
        mWakeLock.setReferenceCounted(false);

        mPolicy = ConfigurationStore.getCurrentPolicy(this);
        mPolicyChangedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mPolicy = (Policy) intent.getSerializableExtra(Policy.EXTRA_NEW_POLICY);
                if (isActivated()) {
                    scheduleStateTransition(SupervisorState.RUNNING);
                }
            }
        };
        Policy.registerPolicyChangedReceiver(this, mPolicyChangedReceiver);

        mBeaconingFinishedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int finishedBeaconingRound =
                        intent.getIntExtra(BeaconingManager.EXTRA_BEACONING_ID, -1);

                if (mBeaconingRoundId != finishedBeaconingRound) {
                    Log.w(TAG, String.format(
                            "Got notification that beaconing round %d finished,"
                                    + " but was expecting notification for beaconing round %d",
                            finishedBeaconingRound, mBeaconingRoundId));
                    return;
                }

                scheduleStateTransition(SupervisorState.SLEEPING);
            }
        };

        // Reset service to previous state
        final SupervisorState previousState = ConfigurationStore.getSupervisorState(this);
        setState(previousState);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction().equals(ACTION_WAKE_UP)) {
            // It's a wake-up call
            scheduleStateTransition(SupervisorState.RUNNING);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (!mState.equals(SupervisorState.SLEEPING)) {
            Log.w(TAG, "Supervisor is shut down extraordinarily!");
        }

        Policy.unregisterPolicyChangedReceiver(this, mPolicyChangedReceiver);
        mHandlerThread.quit();
        releaseWakeLock();
    }

    // SUPERVISOR STATE
    private synchronized void setState(SupervisorState newState) {
        Log.v(TAG, "Transitioning from " + mState + " to " + newState);

        mState.onLeave(this);

        mState = newState;
        ConfigurationStore.saveSupervisorState(this, newState);

        newState.onEnter(this);
    }

    private synchronized void scheduleStateTransition(final SupervisorState newState) {
        mStateHandler.post(new Runnable() {
            @Override
            public void run() {
                if (newState.equals(mState)) {
                    // The supervisor is already in the target state.
                    return;
                } else if (!newState.isPossibleOrigin(mState)) {
                    // The transition is not possible
                    throw new IllegalStateException(
                            "Can not transition from " + mState + " to " + newState);
                }

                // Perform state transition and execute main body of new state
                setState(newState);
                newState.execute(SupervisorService.this);
            }
        });
    }

    private void acquireWakeLock() {
        if (!mWakeLock.isHeld()) {
            mWakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (mWakeLock.isHeld()) {
            Log.v(TAG, "WakeLock released");
            mWakeLock.release();
        }
    }

    private void startListeningForBeacons() {
        mBeaconingManager.setPassive();
    }

    private void stopListeningForBeacons() {
        mBeaconingManager.setStopped();
    }

    private void startSendingBeacons() {
        final int intervalDuration = BeaconingIntervalHandler.MAX_BEACONING_DURATION;
        mBeaconingRoundId = (int) SystemClock.elapsedRealtime() / intervalDuration;
        LocalBroadcastManager.getInstance(this).registerReceiver(
                mBeaconingFinishedReceiver,
                new IntentFilter(BeaconingManager.ACTION_BEACONING_FINISHED));

        mBeaconingManager.setActive(mBeaconingRoundId);
    }

    private void stopSendingBeacons() {
        mBeaconingManager.setPassive();

        LocalBroadcastManager.getInstance(this).unregisterReceiver(mBeaconingFinishedReceiver);
        mBeaconingRoundId = -1;
    }

    private void scheduleNextWakeUp() {
        final BeaconingInterval interval = mPolicy.getBeaconingInterval();
        if (!interval.equals(BeaconingInterval.OFF)) {
            mWakeUpIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(this, WakeUpReceiver.class),
                    PendingIntent.FLAG_UPDATE_CURRENT);

            final long triggerTimeMillis = interval.getNextBeaconTimeMillis();
            mAlarmManager.set(AlarmManager.RTC_WAKEUP, triggerTimeMillis, mWakeUpIntent);
            Log.d(TAG, String.format("Set alarm to wake up again in %d seconds",
                    ((triggerTimeMillis - System.currentTimeMillis()) / 1000)));
        }
    }

    private void cancelWakeUp() {
        if (mWakeUpIntent != null) {
            mAlarmManager.cancel(mWakeUpIntent);
            mWakeUpIntent = null;
            Log.v(TAG, "Cancelled wake up intent");
        }
    }

    // EXTERNAL API
    @Override
    public IBinder onBind(Intent intent) {
        return new SupervisorBinder();
    }

    public boolean isActivated() {
        return ACTIVE_STATES.contains(mState);
    }

    public void activateOppNet() {
        if (!isActivated()) {
            if (mPolicy.allows(Feature.BLUETOOTH)) {
                Log.d(TAG, "Requesting bluetooth discoverable mode upon supervisor activation");
                mNetManager.requestBluetoothDiscoverable();
            }
            scheduleStateTransition(SupervisorState.RUNNING);
        }
    }

    public void changePolicy(Policy newPolicy) {
        mPolicy = newPolicy;
        Policy.setCurrentPolicy(this, newPolicy);
    }

    public void sleep() {
        if (isActivated()) {
            scheduleStateTransition(SupervisorState.SLEEPING);
        }
    }

    public boolean requestBeaconing() {
        if (isActivated()) {
            mBeaconingManager.sendSingleBeacon();
            return true;
        }
        return false;
    }

    public void deactivateOppNet() {
        if (isActivated()) {
            // Clear scheduled state transitions and instead go to IDLE next
            mStateHandler.removeCallbacksAndMessages(null);
            scheduleStateTransition(SupervisorState.IDLE);
        }
    }

    public void addCallback(Callback callback) {
        mCallbacks.add(callback);
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    private void notifyCallbacks(boolean activated) {
        for (Callback callback : mCallbacks) {
            callback.onActivationStateChanged(activated);
        }
    }

    /**
     * Convenience method to start the supervisor service.
     * 
     * @param context
     */
    public static void startSupervisorService(Context context) {
        sendStartCommand(context, ACTION_START);
    }

    public static void wakeupSupervisorService(Context context) {
        final ComponentName supervisor = sendStartCommand(context, ACTION_WAKE_UP);
        Log.v(TAG, "Waking up supervisor: " + (supervisor != null));
    }

    private static ComponentName sendStartCommand(Context context, String action) {
        final Intent startIntent = new Intent(context, SupervisorService.class);
        startIntent.setAction(action);
        return context.startService(startIntent);
    }

    public static boolean bindSupervisorService(Context context, ServiceConnection connection) {
        final Intent bindIntent = new Intent(context, SupervisorService.class);
        return context.bindService(bindIntent, connection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Custom binder which allows retrieving the current supervisor service instance.
     * 
     * @author brunf
     */
    public class SupervisorBinder extends Binder {
        public SupervisorService getSupervisor() {
            return SupervisorService.this;
        }
    }

    public static interface Callback {
        public void onActivationStateChanged(boolean activated);
    }

    /**
     * Receiver for WAKE_UP_SUPERVISOR and BOOT_COMPLETE broadcasts, which start/reactivate the
     * supervisor service.
     * 
     * @author brunf
     */
    public static class WakeUpReceiver extends BroadcastReceiver {
        public WakeUpReceiver() {
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
                    startSupervisorService(context);
                } else {
                    wakeupSupervisorService(context);
                }
            }
        }
    }

    public static enum SupervisorState {
        /**
         * The supervisor service is stopped (not instantiated).
         */
        STOPPED {
            @Override
            public boolean isPossibleOrigin(SupervisorState state) {
                // STOPPED can not be reached from anywhere else
                return false;
            }
        },
        /**
         * The supervisor service is up and running, but doing nothing yet. From here, an activation
         * request (e.g. the user switches OppNet "on" in the UI) is needed to transition further to
         * the {@link SupervisorState#RUNNING RUNNING} state.
         * <p>
         * This state is also transitioned to in case of a deactivation request.
         */
        IDLE {
            @Override
            public void onEnter(SupervisorService sv) {
                sv.cancelWakeUp();
                sv.stopListeningForBeacons();
                sv.notifyCallbacks(false);
                sv.mBeaconingManager.setStopped();
            }

            @Override
            public void onLeave(SupervisorService sv) {
                sv.notifyCallbacks(true);
            }
        },
        /**
         * The platform is enabled. Depending on the policy, the supervisor now activates different
         * platform features (i.e. beaconing). After all work is done, the supervisor automatically
         * transitions into the {@link SupervisorState#SLEEPING SLEEPING} state.
         * <p>
         * When in this state, the supervisor holds a partial wake lock.
         * 
         * @see PowerManager.WakeLock
         */
        RUNNING {
            @Override
            public void onEnter(SupervisorService sv) {
                sv.acquireWakeLock();
                sv.startListeningForBeacons();
                sv.startSendingBeacons();
            }

            @Override
            public void onLeave(SupervisorService sv) {
                sv.stopSendingBeacons();
                sv.releaseWakeLock();
            }
        },
        /**
         * The platform is enabled, but sleeping: An alarm is set to wake up the service again later
         * on. When woken up, the supervisor transitions back to the {@link SupervisorState#RUNNING
         * RUNNING} state.
         * <p>
         * When in this state, the supervisor keeps network sockets open, and holds on to other
         * network-related locks.
         * 
         * @see WifiManager.WifiLock
         * @see WifiManager.MulticastLock
         */
        SLEEPING {
            @Override
            public void onEnter(SupervisorService sv) {
                sv.startListeningForBeacons();
                sv.scheduleNextWakeUp();
            }

            @Override
            public boolean isPossibleOrigin(SupervisorState state) {
                return (state.equals(STOPPED) || state.equals(RUNNING));
            }
        };

        /**
         * Executes when entering this state. By default, this method does nothing.
         * 
         * @param sv
         */
        public void onEnter(SupervisorService sv) {
        }

        /**
         * Executes the main function of this state. By default, this method does nothing.
         * 
         * @param sv
         */
        public void execute(SupervisorService sv) {
        }

        /**
         * Executes when leaving this state. By default, this method does nothing.
         * 
         * @param sv
         */
        public void onLeave(SupervisorService sv) {
        }

        /**
         * Checks if this state can be transitioned to from the specified state. By default, this
         * method returns {@code true}, except the specified state is the same as this one.
         * 
         * @param state the state from which the transition would occur
         * @return true if the transition is possible, false otherwise
         */
        public boolean isPossibleOrigin(SupervisorState state) {
            return !state.equals(this);
        }
    }
}
