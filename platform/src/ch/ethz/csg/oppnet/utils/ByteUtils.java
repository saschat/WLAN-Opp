
package ch.ethz.csg.oppnet.utils;

import com.google.common.io.BaseEncoding;
import com.google.protobuf.ByteString;

/**
 * Collection of utility methods.
 */
public class ByteUtils {
    private final static BaseEncoding HEX = BaseEncoding.base16();

    private ByteUtils() {
        // Not instantiable utility class
    }

    public static String bytesToHex(byte[] bytes) {
        return HEX.encode(bytes);
    }

    public static String bytesToHex(byte[] bytes, int length) {
        return HEX.encode(bytes, 0, length);
    }

    public static String bytesToHex(ByteString bytes) {
        return bytesToHex(bytes.toByteArray());
    }

    public static String bytesToHex(ByteString bytes, int length) {
        return bytesToHex(bytes.toByteArray(), length);
    }
}
