
package ch.ethz.csg.oppnet.apps;

import android.content.Context;
import android.database.Cursor;

import ch.ethz.csg.oppnet.data.DbController;
import ch.ethz.csg.oppnet.data.FullContract;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

public class ApplicationRegistry {
    private static final String LOCK_VALUE = "token_is_being_generated";
    private static ApplicationRegistry sInstance;

    /**
     * Bidirectional map from package names to API keys.
     */
    private final BiMap<String, String> mApplicationMap;

    private final DbController mDbController;

    public static synchronized ApplicationRegistry getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ApplicationRegistry(context);
        }
        return sInstance;
    }

    private ApplicationRegistry(Context context) {
        mDbController = new DbController(context);
        mApplicationMap = initInstalledApplicationsMap(mDbController);
    }

    private HashBiMap<String, String> initInstalledApplicationsMap(DbController dbController) {
        final HashBiMap<String, String> map = HashBiMap.create();

        final Cursor appCursor = dbController.getApplications();

        final int nameColumnIndex = appCursor.getColumnIndex(FullContract.Apps.COLUMN_PACKAGE_NAME);
        final int tokenColumnIndex = appCursor.getColumnIndex(FullContract.Apps.COLUMN_APP_TOKEN);
        while (appCursor.moveToNext()) {
            map.put(appCursor.getString(nameColumnIndex), appCursor.getString(tokenColumnIndex));
        }
        appCursor.close();

        return map;
    }

    public String getPackageFromToken(String apiKey) {
        return mApplicationMap.inverse().get(apiKey);
    }

    public String registerApplication(String packageName) {
        // Lock the current (possibly empty) entry for the given packageName
        String appToken = mApplicationMap.put(packageName, LOCK_VALUE);

        if (appToken == null) {
            // The app is not yet registered - generate new token.
            appToken = mDbController.insertApplication(packageName);
        } else if (appToken.equals(LOCK_VALUE)) {
            // Somebody else is registering this app already, so we back off.
            return null;
        }

        mApplicationMap.put(packageName, appToken);
        return appToken;
    }

    public boolean unregisterApplication(String packageName) {
        String appToken = mApplicationMap.remove(packageName);
        return mDbController.deleteApplication(appToken) > 0;
    }
}
