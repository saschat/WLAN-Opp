
package ch.ethz.csg.oppnet.crypto;

import static org.abstractj.kalium.crypto.Util.slice;

import android.content.Context;
import android.util.Log;

import ch.ethz.csg.oppnet.data.ConfigurationStore;
import ch.ethz.csg.oppnet.data.DbController;

import org.abstractj.kalium.crypto.Hash;
import org.abstractj.kalium.keys.PrivateKey;
import org.abstractj.kalium.keys.SigningKey;

public class MasterKeyUtil {
    private static final String TAG = MasterKeyUtil.class.getSimpleName();

    public static void create(Context context) {
        // Create new Ed25519 private key
        final SigningKey edSecretKey = new SigningKey();

        // Transform to corresponding Curve25519 private key
        final byte[] digest = new Hash().sha512(slice(edSecretKey.toBytes(), 0, 32));
        digest[0] &= 248;
        digest[31] &= 127;
        digest[31] |= 64;
        final PrivateKey curveSecretKey = new PrivateKey(slice(digest, 0, 32));

        if (ConfigurationStore.saveMasterKeys(context, edSecretKey, curveSecretKey)) {
            // Update identity in database
            Log.d(TAG, "Created new identity: " + edSecretKey.getVerifyKey());
            new DbController(context).insertIdentity(
                    "master", edSecretKey.getVerifyKey().toBytes(), null);
        }
    }
}
