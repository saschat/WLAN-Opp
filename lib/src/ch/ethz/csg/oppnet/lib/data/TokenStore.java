
package ch.ethz.csg.oppnet.lib.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class TokenStore {
    private static final String PREFERENCE_FILE = "OppNetPreferences";
    private static final String KEY_APP_TOKEN = "api_key";
    private static final String KEY_LAST_PACKET_RECEIVED = "last_packet_received";

    private static final Set<String> NON_PROTOCOL_KEYS = new HashSet<>();
    static {
        NON_PROTOCOL_KEYS.add(KEY_APP_TOKEN);
        NON_PROTOCOL_KEYS.add(KEY_LAST_PACKET_RECEIVED);
    }

    private TokenStore() {
        // prevent instantiation
    }

    private static SharedPreferences getSharedPreferences(Context context) {
        return context.getSharedPreferences(PREFERENCE_FILE, Context.MODE_PRIVATE);
    }

    private static void saveKeyValue(Context context, String key, String value) {
        final Editor editor = getSharedPreferences(context).edit();
        editor.putString(key, value);
        editor.apply();
    }

    // API KEY
    public static void saveApiKey(Context context, String newApiKey) {
        saveKeyValue(context, KEY_APP_TOKEN, newApiKey);
    }

    public static String getApiKey(Context context) {
        final SharedPreferences prefs = getSharedPreferences(context);
        return prefs.getString(KEY_APP_TOKEN, null);
    }

    // PROTOCOL TOKENS
    public static void saveProtocolToken(Context context, String protocolName, String protocolToken) {
        saveKeyValue(context, protocolName, protocolToken);
    }

    public static HashMap<String, String> getRegisteredProtocolsWithTokens(Context context) {
        final SharedPreferences prefs = getSharedPreferences(context);

        final HashMap<String, String> registeredProtocols = new HashMap<String, String>();
        for (final Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!NON_PROTOCOL_KEYS.contains(entry.getKey())) {
                registeredProtocols.put(entry.getKey(), (String) entry.getValue());
            }
        }
        return registeredProtocols;
    }

    // LAST RECEIVED PACKET
    public static void saveLastPacketReceived(Context context, long timestamp) {
        final Editor editor = getSharedPreferences(context).edit();
        editor.putLong(KEY_LAST_PACKET_RECEIVED, timestamp);
        editor.apply();
    }

    public static long getLastPacketReceived(Context context) {
        final SharedPreferences prefs = getSharedPreferences(context);
        return prefs.getLong(KEY_LAST_PACKET_RECEIVED, 0);
    }
}
