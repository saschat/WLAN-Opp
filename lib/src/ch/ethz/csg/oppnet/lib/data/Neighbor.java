
package ch.ethz.csg.oppnet.lib.data;

import android.database.Cursor;
import android.util.Log;

import ch.ethz.csg.oppnet.lib.data.OppNetContract.Neighbors;
import ch.ethz.csg.oppnet.lib.data.OppNetContract.RemoteProtocols;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;

/**
 * Each OppNet neighbor is represented by a Neighbor object.
 * <p>
 * TODO: Use Optionals instead of nullable fields.
 */
public class Neighbor implements Comparable<Neighbor> {
    public static final int BYTES_SHORT_NODE_ID = 16;

    private final static String TAG = Neighbor.class.getSimpleName();
    private final static BaseEncoding HEX_CODER = BaseEncoding.base16();
    private final static BaseEncoding BASE64_CODER = BaseEncoding.base64Url();

    private final long mRawId;
    private final byte[] mNodeId;
    private final long mTimeLastSeen;
    private final boolean mMulticastCapable;
    private final String mLastSeenNetwork;
    private final Inet4Address mIp4;
    private final Inet6Address mIp6;
    private final byte[] mBt;
    private final HashSet<ByteBuffer> mSupportedProtocols;

    public static Neighbor fromCursor(Cursor dataCursor) {
        final int colIdxRawId = dataCursor.getColumnIndexOrThrow(Neighbors._ID);
        final long rawId = dataCursor.getLong(colIdxRawId);

        // First, all non-NULL fields
        final byte[] nodeId = dataCursor.getBlob(
                dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_IDENTIFIER));
        final long timeLastSeen = dataCursor.getLong(
                dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_TIME_LASTSEEN));
        final int multicastCapable = dataCursor.getInt(
                dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_MULTICAST_CAPABLE));

        // Now all NULLable fields
        Inet4Address ip4Address = null;
        final int colIdxIp4 = dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_IP4);
        if (!dataCursor.isNull(colIdxIp4)) {
            try {
                ip4Address = (Inet4Address) InetAddress.getByAddress(dataCursor.getBlob(colIdxIp4));
            } catch (UnknownHostException e) {
                Log.w(TAG, "Encountered invalid IPv4 address stored for node " + rawId);
            }
        }

        Inet6Address ip6Address = null;
        final int colIdxIp6 = dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_IP6);
        if (!dataCursor.isNull(colIdxIp6)) {
            try {
                ip6Address = (Inet6Address) InetAddress.getByAddress(dataCursor.getBlob(colIdxIp6));
            } catch (UnknownHostException e) {
                Log.w(TAG, "Encountered invalid IPv6 address stored for node " + rawId);
            }
        }

        byte[] btAddress = null;
        final int colIdxBt = dataCursor.getColumnIndex(Neighbors.COLUMN_BLUETOOTH);
        if (!dataCursor.isNull(colIdxBt)) {
            btAddress = dataCursor.getBlob(colIdxBt);
        }

        final String networkName = dataCursor.getString(
                dataCursor.getColumnIndexOrThrow(Neighbors.COLUMN_NETWORK));

        // Last but not least, collect all protocols
        final HashSet<ByteBuffer> supportedProtocols = new HashSet<>();
        final int protocolIndex =
                dataCursor.getColumnIndexOrThrow(RemoteProtocols.COLUMN_PROTOCOL_HASH);

        long currentRawId = rawId;
        while (rawId == currentRawId) {
            final byte[] protocolBytes = dataCursor.getBlob(protocolIndex);
            if (protocolBytes != null) {
                supportedProtocols.add(ByteBuffer.wrap(protocolBytes));
            }

            if (dataCursor.moveToNext()) {
                currentRawId = dataCursor.getLong(colIdxRawId);
            } else {
                break;
            }
        }
        dataCursor.moveToPrevious();

        return new Neighbor(
                rawId, nodeId, timeLastSeen, multicastCapable > 0, networkName,
                ip4Address, ip6Address, btAddress, supportedProtocols);
    }

    private Neighbor(long rawId, byte[] neighborId, long timeLastSeen, boolean multicastCapable,
            String lastSeenNetwork, Inet4Address ip4, Inet6Address ip6, byte[] bt,
            HashSet<ByteBuffer> protocols) {
        mRawId = rawId;
        mNodeId = neighborId;
        mTimeLastSeen = timeLastSeen;
        mMulticastCapable = multicastCapable;
        mLastSeenNetwork = lastSeenNetwork;
        mIp4 = ip4;
        mIp6 = ip6;
        mBt = bt;
        mSupportedProtocols = protocols;
    }

    public long getRawId() {
        return mRawId;
    }

    public byte[] getNodeId() {
        return mNodeId;
    }

    public String getNodeIdAsHex() {
        return HEX_CODER.encode(mNodeId);
    }

    public String getShortNodeIdAsHex() {
        return HEX_CODER.encode(mNodeId, 0, BYTES_SHORT_NODE_ID);
    }

    public String getNodeIdAsBase64() {
        return BASE64_CODER.encode(mNodeId);
    }

    public long getTimeLastSeen() {
        return mTimeLastSeen;
    }

    public boolean isMulticastCapable() {
        return mMulticastCapable;
    }

    public boolean hasLastSeenNetwork() {
        return mLastSeenNetwork != null;
    }

    public String getLastSeenNetwork() {
        return mLastSeenNetwork;
    }

    public boolean hasAnyIpAddress() {
        return (mIp4 != null || mIp6 != null);
    }

    public InetAddress getAnyIpAddress() {
        // Preferrably the IPv4 address
        if (mIp4 != null) {
            return mIp4;
        }
        if (mIp6 != null) {
            return mIp6;
        }
        return null;
    }

    public boolean hasIp4Address() {
        return mIp4 != null;
    }

    public Inet4Address getIp4Address() {
        return mIp4;
    }

    public boolean hasIp6Address() {
        return mIp6 != null;
    }

    public Inet6Address getIp6Address() {
        return mIp6;
    }

    public boolean hasBluetoothAddress() {
        return mBt != null;
    }

    public byte[] getBluetoothAddress() {
        return mBt;
    }

    public ImmutableSet<ByteBuffer> getSupportedProtocols() {
        return ImmutableSet.copyOf(mSupportedProtocols);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof Neighbor)) {
            return false;
        }
        final Neighbor o = (Neighbor) other;
        return Arrays.equals(mNodeId, o.getNodeId());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(mNodeId);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", getShortNodeIdAsHex())
                .add("lastSeen", mTimeLastSeen)
                .toString();
    }

    @Override
    public int compareTo(Neighbor another) {
        final byte[] otherId = another.getNodeId();

        if (this.equals(another)) {
            return 0;
        }

        // Sort in descending order of when the node was last seen
        final long timeDifference = another.getTimeLastSeen() - mTimeLastSeen;
        if (timeDifference == 0) {
            // When last seen at the same time, sort by node ID in descending order
            for (int i = 0; i < mNodeId.length; i++) {
                if (mNodeId[i] != otherId[i]) {
                    return mNodeId[i] - otherId[i];
                }
            }
        }
        return (int) timeDifference;
    }
}
