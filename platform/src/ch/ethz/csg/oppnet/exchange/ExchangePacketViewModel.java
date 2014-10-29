
package ch.ethz.csg.oppnet.exchange;

import android.database.Cursor;

import ch.ethz.csg.oppnet.data.FullContract.PacketQueues;
import ch.ethz.csg.oppnet.data.FullContract.Packets;

import com.google.common.io.BaseEncoding;

import java.util.HashSet;
import java.util.Set;

public class ExchangePacketViewModel {
    private final static BaseEncoding HEX_CODER = BaseEncoding.base16();

    private final long mPacketId;
    private final byte[] mSenderNode;
    private final byte[] mTargetNode;
    private final byte[] mProtocol;
    private final byte[] mPayload;
    private final long mTtl;
    private final byte[] mMac;
    private final Long mTimeReceived;
    private final Set<PacketQueues> mQueues;

    private ExchangePacketViewModel(
            long packetId, byte[] senderNode, byte[] targetNode, byte[] protocol, byte[] payload,
            long ttl, byte[] mac, Long timeReceived, Set<PacketQueues> queues) {
        mPacketId = packetId;
        mSenderNode = senderNode;
        mTargetNode = targetNode;
        mProtocol = protocol;
        mPayload = payload;
        mTtl = ttl;
        mMac = mac;
        mTimeReceived = timeReceived;
        mQueues = queues;
    }

    public long getPacketId() {
        return mPacketId;
    }

    public String getSenderNodeAsHex() {
        if (mSenderNode != null) {
            return HEX_CODER.encode(mSenderNode);
        }
        return null;
    }

    public String getTargetNodeAsHex() {
        if (mTargetNode != null) {
            return HEX_CODER.encode(mTargetNode);
        }
        return null;
    }

    public String getProtocolAsHex() {
        return HEX_CODER.encode(mProtocol);
    }

    public byte[] getPayload() {
        return mPayload;
    }

    public long getTtl() {
        return mTtl;
    }

    public String getMacAsHex() {
        if (mMac != null) {
            return HEX_CODER.encode(mMac);
        }
        return null;
    }

    public Long getTimeReceived() {
        return mTimeReceived;
    }

    public Set<PacketQueues> getPacketQueues() {
        return mQueues;
    }

    public static ExchangePacketViewModel fromCursor(Cursor data) {
        final int colIdxId = data.getColumnIndex(Packets._ID);
        final int colIdxSourceNode = data.getColumnIndexOrThrow(Packets.COLUMN_SOURCE_NODE);
        final int colIdxTargetNode = data.getColumnIndexOrThrow(Packets.COLUMN_TARGET_NODE);
        final int colIdxMac = data.getColumnIndexOrThrow(Packets.COLUMN_MAC);
        final int colIdxTimeReceived = data.getColumnIndexOrThrow(Packets.COLUMN_TIME_RECEIVED);
        final int colIdxQueue = data.getColumnIndexOrThrow(PacketQueues.COLUMN_QUEUE);

        byte[] sourceNode = null;
        if (!data.isNull(colIdxSourceNode)) {
            sourceNode = data.getBlob(colIdxSourceNode);
        }

        byte[] targetNode = null;
        if (!data.isNull(colIdxTargetNode)) {
            targetNode = data.getBlob(colIdxTargetNode);
        }

        byte[] mac = null;
        if (!data.isNull(colIdxMac)) {
            mac = data.getBlob(colIdxMac);
        }

        Long timeReceived = null;
        if (!data.isNull(colIdxTimeReceived)) {
            timeReceived = data.getLong(colIdxTimeReceived);
        }

        final long packetId = data.getLong(colIdxId);
        final byte[] protocol = data.getBlob(data.getColumnIndexOrThrow(Packets.COLUMN_PROTOCOL));
        final byte[] payload = data.getBlob(data.getColumnIndexOrThrow(Packets.COLUMN_PAYLOAD));
        final long ttl = data.getLong(data.getColumnIndexOrThrow(Packets.COLUMN_TTL));

        final Set<PacketQueues> queues = new HashSet<>();
        long currentId = packetId;
        while (currentId == packetId) {
            final int queue = data.getInt(colIdxQueue);
            queues.add(PacketQueues.values()[queue]);

            if (data.moveToNext()) {
                currentId = data.getLong(colIdxId);
            } else {
                break;
            }
        }
        data.moveToPrevious();

        return new ExchangePacketViewModel(
                packetId, sourceNode, targetNode, protocol, payload, ttl, mac, timeReceived,
                queues);
    }
}
