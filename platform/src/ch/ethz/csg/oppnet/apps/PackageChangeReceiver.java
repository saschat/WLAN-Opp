
package ch.ethz.csg.oppnet.apps;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Broadcast receiver for the OppNet client application discovery process.
 * <p>
 * Receives system broadcast related to package management (adding and removing) and starts the
 * suitable action in the {@link AppRegistrationService}.
 * 
 * @author fubu
 */
public class PackageChangeReceiver extends BroadcastReceiver {
    public PackageChangeReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            final Uri data = intent.getData();
            final String appName = data.getEncodedSchemeSpecificPart();
            final Bundle extras = intent.getExtras();

            switch (action) {
                case Intent.ACTION_PACKAGE_ADDED: {
                    if (extras != null && !extras.getBoolean(Intent.EXTRA_REPLACING)) {
                        AppRegistrationService.startIssueApiKey(context, appName);
                    }
                    break;
                }

                case Intent.ACTION_PACKAGE_REMOVED: {
                    if (extras != null && !extras.getBoolean(Intent.EXTRA_REPLACING)) {
                        AppRegistrationService.startRevokeApiKey(context, appName);
                    }
                    break;
                }

                default:
                    // Not a broadcast we can handle
                    break;
            }
        }
    }
}
