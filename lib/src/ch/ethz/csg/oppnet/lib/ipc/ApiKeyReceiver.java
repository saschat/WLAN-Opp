
package ch.ethz.csg.oppnet.lib.ipc;

import android.app.IntentService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import ch.ethz.csg.oppnet.lib.data.TokenStore;

public class ApiKeyReceiver extends BroadcastReceiver {
    public static final String ACTION_ISSUE_API_KEY = "ch.ethz.csg.oppnet.action.ISSUE_API_KEY";

    public static final String EXTRA_APP_NAME = "ch.ethz.csg.oppnet.extra.APP_NAME";
    public static final String EXTRA_API_KEY = "ch.ethz.csg.oppnet.extra.API_KEY";

    /**
     * Sends an intent to the OppNet platform requesting an API key for this OppNet client. The
     * target application must hold the basic OppNet permission and have a broadcast receiver for
     * the ISSUE_API_KEY action defined in its manifest.
     * 
     * @param context A context to start the target service in the OppNet platform.
     * @param appName The name of the application package which should be examined.
     * @see IntentService
     */
    public static void requestApiKey(Context context) {
        final Intent intent = new Intent(ACTION_ISSUE_API_KEY);
        intent.putExtra(EXTRA_APP_NAME, context.getPackageName());
        context.startService(intent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && intent.getAction().equals(ACTION_ISSUE_API_KEY)) {
            final String apiKey = intent.getStringExtra(EXTRA_API_KEY);
            if (apiKey != null) {
                TokenStore.saveApiKey(context, apiKey);
            }
        }
    }
}
