
package ch.ethz.csg.oppnet.lib.data;

import android.content.Context;

import java.util.HashMap;
import java.util.Set;

public class ProtocolRegistry {
    private static ProtocolRegistry sInstance;

    private final Context mContext;

    /**
     * Caches the mapping of protocolName -> protocolToken.
     */
    private final HashMap<String, String> mProtocolTokenCache;

    public static synchronized ProtocolRegistry getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ProtocolRegistry(context);
        }
        return sInstance;
    }

    private ProtocolRegistry(Context context) {
        mContext = context;
        mProtocolTokenCache = TokenStore.getRegisteredProtocolsWithTokens(mContext);
    }

    public boolean contains(String protocolName) {
        return mProtocolTokenCache.containsKey(protocolName);
    }

    public void add(String protocolName, String protocolToken) {
        TokenStore.saveProtocolToken(mContext, protocolName, protocolToken);
        mProtocolTokenCache.put(protocolName, protocolToken);
    }

    public String getToken(String protocolName) {
        return mProtocolTokenCache.get(protocolName);
    }

    public String getSingleToken() {
        if (mProtocolTokenCache.size() != 1) {
            throw new IllegalStateException(
                    String.format("Ambigous call: There is %s protocol registered.",
                            mProtocolTokenCache.size() > 1 ? "more than one" : "not even one"));
        }
        return (String) mProtocolTokenCache.values().toArray()[0];
    }

    public Set<String> getRegisteredProtocols() {
        return mProtocolTokenCache.keySet();
    }
}
