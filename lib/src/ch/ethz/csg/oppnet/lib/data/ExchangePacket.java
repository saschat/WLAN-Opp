
package ch.ethz.csg.oppnet.lib.data;

import android.database.Cursor;

import ch.ethz.csg.oppnet.lib.data.OppNetContract.Packets;

import com.google.common.io.BaseEncoding;

public class ExchangePacket {
    private final static BaseEncoding HEX_CODER = BaseEncoding.base16();

    private final long mPacketId;
    private final byte[] mSourceNode;
    private final byte[] mPayload;
    private final long mTimeReceived;

    private ExchangePacket(long packetId, byte[] sourceNode, byte[] payload, long timeReceived) {
        mPacketId = packetId;
        mSourceNode = sourceNode;
        mPayload = payload;
        mTimeReceived = timeReceived;
    }

    public long getPacketId() {
        return mPacketId;
    }

    public String getSourceNodeAsHex() {
        if (mSourceNode != null) {
            return HEX_CODER.encode(mSourceNode);
        }
        return null;
    }

    public byte[] getPayload() {
        return mPayload;
    }

    public long getTimeReceived() {
        return mTimeReceived;
    }

    public static ExchangePacket fromCursor(Cursor data) {
        final int colIdxSourceNode = data.getColumnIndex(Packets.COLUMN_SOURCE_NODE);
        byte[] sourceNode = null;
        if (!data.isNull(colIdxSourceNode)) {
            sourceNode = data.getBlob(colIdxSourceNode);
        }

        return new ExchangePacket(
                data.getLong(data.getColumnIndex(Packets._ID)),
                sourceNode,
                data.getBlob(data.getColumnIndex(Packets.COLUMN_PAYLOAD)),
                data.getLong(data.getColumnIndex(Packets.COLUMN_TIME_RECEIVED)));
    }
}
