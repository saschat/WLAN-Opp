
package ch.ethz.csg.oppnet.crypto;

import static com.google.common.base.Preconditions.checkArgument;
import static org.abstractj.kalium.SodiumConstants.BOXZERO_BYTES;
import static org.abstractj.kalium.SodiumConstants.NONCE_BYTES;
import static org.abstractj.kalium.SodiumConstants.PUBLICKEY_BYTES;
import static org.abstractj.kalium.SodiumConstants.ZERO_BYTES;
import static org.abstractj.kalium.crypto.Util.isValid;
import static org.abstractj.kalium.crypto.Util.slice;

import android.content.Context;

import ch.ethz.csg.oppnet.data.ConfigurationStore;
import ch.ethz.csg.oppnet.utils.ByteUtils;

import org.abstractj.kalium.SodiumJNI;
import org.abstractj.kalium.crypto.Hash;
import org.abstractj.kalium.crypto.Random;
import org.abstractj.kalium.encoders.Encoder;
import org.abstractj.kalium.keys.KeyPair;
import org.abstractj.kalium.keys.PrivateKey;
import org.abstractj.kalium.keys.SigningKey;
import org.abstractj.kalium.keys.VerifyKey;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class CryptoHelper {
    private static final int BOX_MACBYTES = ZERO_BYTES - BOXZERO_BYTES;

    public static boolean testNativeLibrary() {
        // Create new Ed25519 private key
        final SigningKey edSecretKey = new SigningKey();

        // Transform to corresponding Curve25519 private key
        final byte[] digest = new Hash().sha512(slice(edSecretKey.toBytes(), 0, 32));
        digest[0] &= 248;
        digest[31] &= 127;
        digest[31] |= 64;
        final PrivateKey curveSecretKey = new PrivateKey(slice(digest, 0, 32));

        // Create public keys in different ways and compare the results
        final byte[] originalCurvePublicKey =
                new KeyPair(curveSecretKey.toBytes()).getPublicKey().toBytes();
        final byte[] convertedCurvePublicKey =
                CryptoHelper.convertEdPublicKeyToCurve(edSecretKey.getVerifyKey().toBytes());
        return Arrays.equals(originalCurvePublicKey, convertedCurvePublicKey);
    }

    // throws RuntimeException
    protected static byte[] convertEdPublicKeyToCurve(byte[] publicEdKey) {
        checkArgument(publicEdKey != null && publicEdKey.length == PUBLICKEY_BYTES,
                "Invalid format for public key");

        final byte[] publicCurveKey = new byte[PUBLICKEY_BYTES];
        isValid(SodiumJNI.crypto_sign_ed25519_convert_key(publicCurveKey, publicEdKey),
                String.format("Could not convert public key '%s' to Curve25519 format!",
                        Encoder.HEX.encode(publicEdKey)));
        return publicCurveKey;
    }

    // throws RuntimeException
    public static byte[] encrypt(Context context, byte[] plaintext, byte[] receiverPublicKey) {
        // Convert receiver's public key to Curve25519 format
        final byte[] receiverPublicCurveKey = convertEdPublicKeyToCurve(receiverPublicKey);

        // Get own private Curve25519 key
        PrivateKey senderSecretKey = ConfigurationStore.getMasterEncryptionKey(context);

        // Encrypt message inside NaCl box
        final int ciphertextLength = plaintext.length + BOX_MACBYTES;
        final byte[] ciphertext = new byte[ciphertextLength + NONCE_BYTES];
        final byte[] nonce = new Random().randomBytes(NONCE_BYTES);
        isValid(SodiumJNI.crypto_box_easy(ciphertext, plaintext, plaintext.length, nonce,
                receiverPublicCurveKey, senderSecretKey.toBytes()), "Encryption failed");

        // Append nonce to ciphertext and return it
        System.arraycopy(nonce, 0, ciphertext, ciphertextLength, NONCE_BYTES);
        return ciphertext;
    }

    // throws RuntimeException
    public static byte[] decrypt(Context context, byte[] ciphertextAndNonce, byte[] senderPublicKey) {
        // Convert sender's public key to Curve25519 format
        final byte[] senderPublicCurveKey = convertEdPublicKeyToCurve(senderPublicKey);

        // Get own private Curve25519 key
        PrivateKey receiverSecretKey = ConfigurationStore.getMasterEncryptionKey(context);

        // Decrypt message from NaCl box
        final byte[] ciphertext = Arrays.copyOf(
                ciphertextAndNonce, ciphertextAndNonce.length - NONCE_BYTES);
        final byte[] nonce = Arrays.copyOfRange(
                ciphertextAndNonce, ciphertext.length, ciphertextAndNonce.length);

        final byte[] plaintext = new byte[ciphertext.length - BOX_MACBYTES];
        isValid(SodiumJNI.crypto_box_open_easy(plaintext, ciphertext, ciphertext.length, nonce,
                senderPublicCurveKey, receiverSecretKey.toBytes()), "Decryption failed");

        return plaintext;
    }

    public static byte[] sign(Context context, byte[] data) {
        final SigningKey signingKey = ConfigurationStore.getMasterSigningKey(context);
        return signingKey.sign(data);
    }

    public static boolean verify(byte[] data, byte[] signature, byte[] senderPublicKey) {
        final VerifyKey verifyKey = new VerifyKey(senderPublicKey);
        return verifyKey.verify(data, signature);
    }

    public static byte[] createDigest(byte[] data) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA1");
        } catch (NoSuchAlgorithmException e) {
            // SHA1 is expected to be available
            throw new UnsupportedOperationException(
                    "SHA1 digest function not present on this device.");
        }
        md.update(data);
        return md.digest();
    }

    public static String createHexDigest(byte[] data) {
        final byte[] digest = createDigest(data);
        return ByteUtils.bytesToHex(digest);
    }

}
