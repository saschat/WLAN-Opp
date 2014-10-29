
package ch.ethz.csg.oppnet.exchange;

import android.content.ContentValues;
import android.database.Cursor;

import ch.ethz.csg.oppnet.crypto.CryptoHelper;
import ch.ethz.csg.oppnet.data.FullContract.Packets;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.TransportPacket;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.TransportPacket.Builder;

import com.google.protobuf.ByteString;

public class TransportPacketFactory {
    public static TransportPacket.Builder fromCursor(Cursor dataCursor) {
        final Builder packetBuilder = TransportPacket.newBuilder();

        final byte[] sourceNode = dataCursor.getBlob(
                dataCursor.getColumnIndexOrThrow(Packets.COLUMN_SOURCE_NODE));
        if (sourceNode != null) {
            packetBuilder.setSourceNode(ByteString.copyFrom(sourceNode));
        }

        final byte[] targetNode = dataCursor.getBlob(
                dataCursor.getColumnIndexOrThrow(Packets.COLUMN_TARGET_NODE));
        if (targetNode != null) {
            packetBuilder.setTargetNode(ByteString.copyFrom(targetNode));
        }

        // "protocol", "payload" and "ttl" are always set
        packetBuilder.setProtocol(ByteString.copyFrom(
                dataCursor.getBlob(dataCursor.getColumnIndex(Packets.COLUMN_PROTOCOL))));
        packetBuilder.setPayload(ByteString.copyFrom(
                dataCursor.getBlob(dataCursor.getColumnIndex(Packets.COLUMN_PAYLOAD))));
        packetBuilder.setTtl(
                dataCursor.getLong(dataCursor.getColumnIndex(Packets.COLUMN_TTL)));

        final byte[] mac = dataCursor.getBlob(
                dataCursor.getColumnIndexOrThrow(Packets.COLUMN_MAC));
        if (mac != null) {
            packetBuilder.setMac(ByteString.copyFrom(mac));
        }

        return packetBuilder;
    }

    public static TransportPacket unsignedFromContentValues(ContentValues data) {
        final Builder packetBuilder = TransportPacket.newBuilder();

        final byte[] sourceNode = data.getAsByteArray(Packets.COLUMN_SOURCE_NODE);
        if (sourceNode != null) {
            packetBuilder.setSourceNode(ByteString.copyFrom(sourceNode));
        }

        final byte[] targetNode = data.getAsByteArray(Packets.COLUMN_TARGET_NODE);
        if (targetNode != null) {
            packetBuilder.setTargetNode(ByteString.copyFrom(targetNode));
        }

        // "protocol", "payload" and "ttl" are always set
        packetBuilder.setProtocol(ByteString.copyFrom(
                data.getAsByteArray(Packets.COLUMN_PROTOCOL)));
        packetBuilder.setPayload(ByteString.copyFrom(
                data.getAsByteArray(Packets.COLUMN_PAYLOAD)));
        packetBuilder.setTtl(data.getAsLong(Packets.COLUMN_TTL));

        return packetBuilder.build();
    }

    public static ContentValues toContentValues(TransportPacket packet) {
        final ContentValues data = new ContentValues();

        final ByteString sourceNode = packet.getSourceNode();
        if (!sourceNode.isEmpty()) {
            data.put(Packets.COLUMN_SOURCE_NODE, sourceNode.toByteArray());
        }

        final ByteString targetNode = packet.getTargetNode();
        if (!targetNode.isEmpty()) {
            data.put(Packets.COLUMN_TARGET_NODE, targetNode.toByteArray());
        }

        final ByteString mac = packet.getMac();
        if (!mac.isEmpty()) {
            data.put(Packets.COLUMN_MAC, mac.toByteArray());
        }

        data.put(Packets.COLUMN_TTL, packet.getTtl());
        data.put(Packets.COLUMN_PROTOCOL, packet.getProtocol().toByteArray());
        data.put(Packets.COLUMN_PAYLOAD, packet.getPayload().toByteArray());
        data.put(Packets.COLUMN_TIME_RECEIVED, System.currentTimeMillis() / 1000);
        data.put(Packets.COLUMN_PACKET_HASH,
                CryptoHelper.createDigest(packet.toByteArray()));

        return data;
    }
}
