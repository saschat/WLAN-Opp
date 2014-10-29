
package ch.ethz.csg.oppnet.data;

import android.database.Cursor;

import ch.ethz.csg.oppnet.data.FullContract.Identities;

public class Identity {
    private final byte[] mPublicKey;
    private final String mDisplayName;

    public static Identity fromCursor(Cursor cursor) {
        final byte[] publicKey =
                cursor.getBlob(cursor.getColumnIndex(Identities.COLUMN_PUBLICKEY));
        final String displayName =
                cursor.getString(cursor.getColumnIndex(Identities.COLUMN_DISPLAY_NAME));

        return new Identity(publicKey, displayName);
    }

    private Identity(byte[] publicKey, String displayName) {
        mPublicKey = publicKey;
        mDisplayName = displayName;
    }

    public byte[] getPublicKey() {
        return mPublicKey;
    }

    public String getDisplayName() {
        return mDisplayName;
    }
}
