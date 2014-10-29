
package ch.ethz.csg.oppnet.data;

import android.database.Cursor;

import com.google.common.base.Objects;
import com.google.common.io.BaseEncoding;

/**
 * Simple wrapper around cursor retrieved from querying the implementation details view.
 * 
 * @author brunf
 */
public class Implementation {
    private final static BaseEncoding HEX_CODER = BaseEncoding.base16();

    public static final int DEFAULT_TTL = 60 * 60 * 24;

    private final String mToken;

    // app specific fields
    private final String mPackageName;

    // protocol specific fields
    private final byte[] mProtocolHash;
    private final String mProtocolName;
    private final boolean mIsEncrypted;
    private final boolean mIsSigned;
    private final int mDefaultTtl;

    // identity specific fields
    private final byte[] mIdentity;
    private final String mDisplayName;

    private Implementation(String token, String packageName, String protocolName,
            byte[] protocolHash, boolean isEncrypted, boolean isSigned, Integer defaultTtl,
            byte[] identity, String displayName) {
        mToken = token;

        mPackageName = packageName;

        mProtocolHash = protocolHash;
        mProtocolName = protocolName;
        mIsEncrypted = isEncrypted;
        mIsSigned = isSigned;
        mDefaultTtl = defaultTtl;

        mIdentity = identity;
        mDisplayName = displayName;
    }

    public static Implementation fromCursor(Cursor cursor) {
        final String token = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.Implementations.COLUMN_TOKEN));
        final String packageName = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.Apps.COLUMN_PACKAGE_NAME));
        final String protocolName = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_IDENTIFIER));
        final byte[] protocolHash = cursor.getBlob(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_IDENTIFIER_HASH));
        final int encrypted = cursor.getInt(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_ENCRYPTED));
        final int signed = cursor.getInt(
                cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_SIGNED));
        final byte[] identity = cursor.getBlob(
                cursor.getColumnIndexOrThrow(FullContract.Identities.COLUMN_PUBLICKEY));
        final String displayName = cursor.getString(
                cursor.getColumnIndexOrThrow(FullContract.Identities.COLUMN_DISPLAY_NAME));

        Integer ttl = null;
        if (!cursor.isNull(cursor.getColumnIndexOrThrow(FullContract.Protocols.COLUMN_DEFAULT_TTL))) {
            ttl = cursor.getInt(cursor.getColumnIndex(FullContract.Protocols.COLUMN_DEFAULT_TTL));
        }

        return new Implementation(token, packageName, protocolName,
                protocolHash, encrypted > 0, signed > 0, ttl, identity, displayName);
    }

    public String getToken() {
        return mToken;
    }

    public String getPackageName() {
        return mPackageName;
    }

    public String getProtocolName() {
        return mProtocolName;
    }

    public byte[] getProtocolHash() {
        return mProtocolHash;
    }
    
    public String getProtocolHashAsHex() {
        return HEX_CODER.encode(mProtocolHash);
    }

    public boolean isEncrypted() {
        return mIsEncrypted;
    }

    public boolean isSigned() {
        return mIsSigned;
    }

    public int getDefaultTtl() {
        Integer ttl = mDefaultTtl;
        if (ttl == null) {
            ttl = DEFAULT_TTL;
        }
        return ttl;
    }

    public byte[] getIdentity() {
        return mIdentity;
    }
    
    public String getDisplayName() {
        return mDisplayName;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mPackageName, mProtocolName);
    }
}
