
package ch.ethz.csg.oppnet.data;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import ch.ethz.csg.oppnet.core.Policy;
import ch.ethz.csg.oppnet.core.SupervisorService;
import ch.ethz.csg.oppnet.core.SupervisorService.SupervisorState;

import org.abstractj.kalium.encoders.Encoder;
import org.abstractj.kalium.keys.PrivateKey;
import org.abstractj.kalium.keys.SigningKey;

public class ConfigurationStore {
    private static final String TAG = ConfigurationStore.class.getSimpleName();

    private static final String PREFERENCE_FILE = "OppNetPreferences";

    private static final String KEY_FIRST_RUN = "first_run";
    private static final String KEY_MASTER_SIGNING_KEY = "master_signing_key";
    private static final String KEY_MASTER_ENCRYPTION_KEY = "master_encryption_key";
    private static final String KEY_CURRENT_POLICY = "current_policy";
    private static final String KEY_SUPERVISOR_STATE = "supervisor_state";
    private static final String KEY_BLUETOOTH_ADDRESS = "bluetooth_address";
    private static final String KEY_LAST_BEACONING_CHANGE = "time_last_beaconing_change";

    private ConfigurationStore() {
        // prevent instantiation
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE);
    }

    public static synchronized boolean isFirstRun(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        if (!config.contains(KEY_FIRST_RUN)) {
            final long currentTimeMillis = System.currentTimeMillis();
            config.edit().putLong(KEY_FIRST_RUN, currentTimeMillis).apply();
            return true;
        }
        return false;
    }

    // MASTER KEY
    public static SigningKey getMasterSigningKey(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String hexKey = config.getString(KEY_MASTER_SIGNING_KEY, null);
        try {
            return new SigningKey(hexKey, Encoder.HEX);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static PrivateKey getMasterEncryptionKey(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String hexKey = config.getString(KEY_MASTER_ENCRYPTION_KEY, null);
        try {
            return new PrivateKey(hexKey);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static synchronized boolean saveMasterKeys(
            Context context, SigningKey edSecretKey, PrivateKey curveSecretKey) {

        final SharedPreferences config = getSharedPreferences(context);
        if (config.contains(KEY_MASTER_SIGNING_KEY) || config.contains(KEY_MASTER_ENCRYPTION_KEY)) {
            return false;
        }

        config.edit()
                .putString(KEY_MASTER_SIGNING_KEY, edSecretKey.toString())
                .putString(KEY_MASTER_ENCRYPTION_KEY, curveSecretKey.toString())
                .apply();
        return true;
    }

    // CURRENT POLICY
    public static Policy getCurrentPolicy(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String currentPolicy = config.getString(KEY_CURRENT_POLICY, null);
        return (currentPolicy == null ? Policy.DEFAULT_POLICY : Policy.valueOf(currentPolicy));
    }

    public static void saveCurrentPolicy(Context context, Policy policy) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_CURRENT_POLICY, policy.name())
                .apply();
    }

    // SUPERVISOR STATE
    public static SupervisorState getSupervisorState(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        final String state = config.getString(KEY_SUPERVISOR_STATE, null);
        return (state == null ? SupervisorService.DEFAULT_STATE : SupervisorState.valueOf(state));
    }

    public static void saveSupervisorState(Context context, SupervisorState state) {
        getSharedPreferences(context)
                .edit()
                .putString(KEY_SUPERVISOR_STATE, state.name())
                .apply();
    }

    // BLUETOOTH ADDRESS
    public static String getBluetoothAddress(Context context) {
        return getSharedPreferences(context).getString(KEY_BLUETOOTH_ADDRESS, null);
    }

    public static void saveBluetoothAddress(Context context, String btAddress) {
        checkNotNull(btAddress);

        final SharedPreferences config = getSharedPreferences(context);
        if (config.contains(KEY_BLUETOOTH_ADDRESS)) {
            // There is already one stored
            final String currentAddress = config.getString(KEY_BLUETOOTH_ADDRESS, null);
            if (currentAddress.equals(btAddress)) {
                // It's the same, nothing has changed.
                return;
            }
            Log.w(TAG, "Bluetooth has changed from " + currentAddress + " to " + btAddress);
        }

        config.edit().putString(KEY_BLUETOOTH_ADDRESS, btAddress).apply();
    }

    // LAST BEACONING STATE CHANGE TIME
    public static long getLastBeaconingStateChangeTime(Context context) {
        final SharedPreferences config = getSharedPreferences(context);
        return config.getLong(KEY_LAST_BEACONING_CHANGE, 0);
    }

    public static void saveLastBeaconingStateChangeTime(Context context, long timestamp) {
        getSharedPreferences(context)
                .edit()
                .putLong(KEY_LAST_BEACONING_CHANGE, timestamp)
                .apply();
    }
}
