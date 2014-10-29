
package ch.ethz.csg.oppnet.apps;

import android.content.Context;
import android.database.Cursor;
import android.os.Bundle;

import ch.ethz.csg.oppnet.data.DbController;
import ch.ethz.csg.oppnet.data.Implementation;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.TransportPacket;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSetMultimap;

import java.nio.ByteBuffer;
import java.util.Set;

public class ProtocolRegistry {
    private static ProtocolRegistry sInstance;

    /**
     * Multimap from protocol hash to ProtocolImplementations of this protocol.
     */
    private final HashMultimap<ByteBuffer, Implementation> mProtocolMap;

    private final DbController mDbController;

    public static synchronized ProtocolRegistry getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ProtocolRegistry(context);
        }
        return sInstance;
    }

    private ProtocolRegistry(Context context) {
        mDbController = new DbController(context);
        mProtocolMap = loadProtocolImplementations(mDbController);
    }

    private static HashMultimap<ByteBuffer, Implementation> loadProtocolImplementations(
            DbController dbController) {
        final HashMultimap<ByteBuffer, Implementation> multimap = HashMultimap.create();

        final Cursor implCursor = dbController.getImplementations(null, null);
        while (implCursor.moveToNext()) {
            final Implementation impl = Implementation.fromCursor(implCursor);
            multimap.put(ByteBuffer.wrap(impl.getProtocolHash()), impl);
        }
        implCursor.close();

        return multimap;
    }

    public boolean hasProtocolImplementations(ByteBuffer protocol) {
        return mProtocolMap.containsKey(protocol);
    }

    public Set<Implementation> getProtocolImplementations(byte[] protocol) {
        return mProtocolMap.get(ByteBuffer.wrap(protocol));
    }

    public ImmutableSetMultimap<ByteBuffer, Implementation> getAllProtocolImplementations() {
        return ImmutableSetMultimap.copyOf(mProtocolMap);
    }

    public String getProtocolNameFromPacket(TransportPacket packet) {
        final Set<Implementation> implementations =
                getProtocolImplementations(packet.getProtocol().toByteArray());
        if (!implementations.isEmpty()) {
            final Implementation anyImplementation = implementations.iterator().next();
            return anyImplementation.getProtocolName();
        }
        return null;
    }

    public Implementation registerProtocol(String apiKey, Bundle protocolDescription) {
        Implementation implementation =
                mDbController.insertImplementation(apiKey, protocolDescription);

        if (implementation != null) {
            mProtocolMap.put(ByteBuffer.wrap(implementation.getProtocolHash()), implementation);
        }

        return implementation;
    }
}
