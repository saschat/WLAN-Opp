
package ch.ethz.csg.oppnet.apps;

import android.app.IntentService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import ch.ethz.csg.oppnet.core.OppNetApplication;
import ch.ethz.csg.oppnet.lib.ipc.ApiKeyReceiver;

import java.util.ArrayList;
import java.util.List;

/**
 * An {@link IntentService} subclass handling OppNet client application registration requests.
 * <p>
 * TODO: explain message flow
 */
public class AppRegistrationService extends IntentService {
    private static final String TAG = AppRegistrationService.class.getSimpleName();

    private static final String ACTION_BULK_ISSUE_API_KEYS = "ch.ethz.csg.oppnet.action.BULK_ISSUE_API_KEYS";
    private static final String ACTION_REVOKE_API_KEY = "ch.ethz.csg.oppnet.action.REVOKE_API_KEY";

    /**
     * Starts this service to examine an application package if an OppNet API key should be issued.
     * The target application must hold the basic OppNet permission and have a broadcast receiver
     * for the NEW_API_KEY action defined in its manifest.
     * 
     * @param context A context to start this IntentService.
     * @param appName The name of the application package which should be examined.
     * @see IntentService
     */
    public static void startIssueApiKey(Context context, String appName) {
        final Intent intent = new Intent(context, AppRegistrationService.class);
        intent.setAction(ApiKeyReceiver.ACTION_ISSUE_API_KEY);
        intent.putExtra(ApiKeyReceiver.EXTRA_APP_NAME, appName);
        context.startService(intent);
    }

    /**
     * Starts this service to issue API keys to all OppNet client applications currently installed
     * on the device. This is usually only needed on the first run of the whole application.
     * 
     * @param context A context to start this IntentService.
     * @see IntentService
     */
    public static void startBulkIssueApiKeys(Context context) {
        final Intent intent = new Intent(context, AppRegistrationService.class);
        intent.setAction(ACTION_BULK_ISSUE_API_KEYS);
        context.startService(intent);
    }

    /**
     * Starts this service to revoke an API key previously issued to the specified application. This
     * should usually only be called when removing an application from the device.
     * 
     * @param context A context to start this IntentService.
     * @param appName The name of the application package whose API key should be revoked.
     * @see IntentService
     */
    public static void startRevokeApiKey(Context context, String appName) {
        final Intent intent = new Intent(context, AppRegistrationService.class);
        intent.setAction(ACTION_REVOKE_API_KEY);
        intent.putExtra(ApiKeyReceiver.EXTRA_APP_NAME, appName);
        context.startService(intent);
    }

    public AppRegistrationService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String appName = intent.getStringExtra(ApiKeyReceiver.EXTRA_APP_NAME);
            if (appName != null && appName.equals(getPackageName())) {
                return;
            }

            switch (intent.getAction()) {
                case ApiKeyReceiver.ACTION_ISSUE_API_KEY: {
                    issueApiKey(appName);
                    break;
                }
                case ACTION_BULK_ISSUE_API_KEYS: {
                    bulkIssueApiKeys();
                    break;
                }
                case ACTION_REVOKE_API_KEY: {
                    revokeApiKey(appName);
                    break;
                }
                default: {
                    // no intent we can handle
                    break;
                }
            }
        }
    }

    /**
     * Retrieves a list of broadcast receivers registered in the {@link PackageManager}.
     * 
     * @return a list of receivers which respond to the {@link ApiKeyReceiver#ACTION_ISSUE_API_KEY
     *         ISSUE_API_KEY} broadcast action (may be empty if there are none yet)
     */
    private List<ActivityInfo> getInterestingReceivers() {
        final PackageManager pm = getPackageManager();
        final Intent queryIntent = new Intent(ApiKeyReceiver.ACTION_ISSUE_API_KEY);

        final List<ActivityInfo> receivers = new ArrayList<>();
        final List<ResolveInfo> possibleMatches = pm.queryBroadcastReceivers(queryIntent, 0);
        if (possibleMatches != null) {
            for (ResolveInfo match : possibleMatches) {
                ActivityInfo receiver = match.activityInfo;
                if (receiver != null) {
                    receivers.add(receiver);
                }
            }
        }

        return receivers;
    }

    /**
     * Generates and sends an API key to a client app. An already existing API key is recycled.
     * 
     * @param appName The package name of the client app.
     * @param targetCls The name of the broadcast receiver in the client app which should receive
     *            the broadcast containing the API key.
     * @return true if an API key had to be issued, false otherwise
     */
    private boolean sendApiKey(String appName, String targetCls) {
        final OppNetApplication app = (OppNetApplication) getApplication();
        final String apiKey = app.applicationRegistry.registerApplication(appName);

        if (apiKey != null) {
            final Intent intent = new Intent(ApiKeyReceiver.ACTION_ISSUE_API_KEY);
            intent.setComponent(new ComponentName(appName, targetCls));
            intent.putExtra(ApiKeyReceiver.EXTRA_API_KEY, apiKey);
            sendBroadcast(intent);
            return true;
        }

        return false;
    }

    /**
     * Handles an issuing request for the given package name. Checks if the specified application
     * holds the required permission and contains a suitable broadcast receiver. If so, an API key
     * is being generated by the OppNet platform and broadcast back to the application.
     * 
     * @param appName The name of the application package which should receive an API key.
     */
    private void issueApiKey(String appName) {
        // Find component of freshly installed app which can handle an ISSUE_API_KEY broadcast.
        for (ActivityInfo receiver : getInterestingReceivers()) {
            if (appName.equals(receiver.packageName)) {
                sendApiKey(appName, receiver.name);
                return;
            }
        }

        Log.d(TAG, "No target found for app " + appName);
    }

    /**
     * Handles issuing API keys for all OppNet client applications installed on the device.
     */
    private void bulkIssueApiKeys() {
        final String ownPackage = getPackageName();

        for (final ActivityInfo receiver : getInterestingReceivers()) {
            if (!ownPackage.equals(receiver.packageName)) {
                sendApiKey(receiver.packageName, receiver.name);
            }
        }
    }

    /**
     * Revokes previously issued API keys for the given package name.
     * 
     * @param appName The name of the application package whose API keys should be revoked.
     */
    private void revokeApiKey(String appName) {
        final OppNetApplication app = (OppNetApplication) getApplication();
        app.applicationRegistry.unregisterApplication(appName);
    }
}
