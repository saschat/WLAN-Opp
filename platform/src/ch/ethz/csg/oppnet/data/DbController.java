
package ch.ethz.csg.oppnet.data;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import ch.ethz.csg.oppnet.apps.ProtocolRegistry;
import ch.ethz.csg.oppnet.apps.TokenGenerator;
import ch.ethz.csg.oppnet.crypto.CryptoHelper;
import ch.ethz.csg.oppnet.data.FullContract.Apps;
import ch.ethz.csg.oppnet.data.FullContract.Identities;
import ch.ethz.csg.oppnet.data.FullContract.Implementations;
import ch.ethz.csg.oppnet.data.FullContract.NeighborProtocols;
import ch.ethz.csg.oppnet.data.FullContract.Neighbors;
import ch.ethz.csg.oppnet.data.FullContract.PacketQueues;
import ch.ethz.csg.oppnet.data.FullContract.Packets;
import ch.ethz.csg.oppnet.data.FullContract.ProtocolNeighbors;
import ch.ethz.csg.oppnet.data.FullContract.Protocols;
import ch.ethz.csg.oppnet.data.FullContract.RemoteProtocols;
import ch.ethz.csg.oppnet.exchange.TransportPacketFactory;
import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.lib.data.OppNetContract;
import ch.ethz.csg.oppnet.protobuf.OppNetProtos.TransportPacket;
import ch.ethz.csg.oppnet.utils.ByteUtils;

import com.google.protobuf.ByteString;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DbController {
    private static final String TAG = DbController.class.getSimpleName();

    private final Context mContext;
    private final DbHelper mDbHelper;

    public DbController(Context context) {
        mContext = context;
        mDbHelper = DbHelper.getInstance(context);
    }

    public Identity getMasterIdentity() {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        final Cursor identityCursor = db.query(
                Identities.TABLE_NAME,
                Identities.PROJECTION_DEFAULT,
                Identities._ID + " = 1",
                null, null, null, null, "1");

        try {
            if (!identityCursor.moveToFirst()) {
                throw new IllegalStateException("No master identity found!");
            }
            return Identity.fromCursor(identityCursor);
        } finally {
            identityCursor.close();
        }
    }

    public Implementation getImplementation(String accessToken) {
        return getImplementation(Implementations.COLUMN_TOKEN, accessToken);
    }

    public Implementation getImplementation(long id) {
        return getImplementation(Implementations._ID, String.valueOf(id));
    }

    private Implementation getImplementation(String whereColumn, String whereArg) {
        Implementation implementation = null;

        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        final Cursor cursor = db.query(
                Implementations.VIEW_NAME_FULL_DETAILS,
                Implementations.PROJECTION_DEFAULT,
                whereColumn + " = ?",
                new String[] {
                    whereArg
                },
                null, null, null, "1");

        if (cursor.moveToFirst()) {
            implementation = Implementation.fromCursor(cursor);
        }

        cursor.close();
        return implementation;
    }

    public Cursor getImplementations(String whereColumn, String[] whereArgs) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        final Cursor implCursor = db.query(
                Implementations.VIEW_NAME_FULL_DETAILS,
                Implementations.PROJECTION_DEFAULT,
                null, null, null, null,
                Implementations.SORT_ORDER_DEFAULT);

        return implCursor;
    }

    private long getApplicationIdByToken(String appToken) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        final Cursor appCursor = db.query(
                Apps.TABLE_NAME,
                new String[] {
                    Apps._ID
                },
                Apps.COLUMN_APP_TOKEN + " = ?",
                new String[] {
                    appToken
                },
                null, null, null, "1");

        try {
            if (!appCursor.moveToFirst()) {
                return -1;
            }
            return appCursor.getLong(0);
        } finally {
            appCursor.close();
        }
    }

    public Cursor getApplications() {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        final Cursor appCursor = db.query(
                Apps.TABLE_NAME,
                Apps.PROJECTION_DEFAULT,
                null, null, null, null,
                Apps.SORT_ORDER_DEFAULT);

        return appCursor;
    }

    private long getProtocolIdByName(String protocolName) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        final String protocolHashAsString = CryptoHelper.createHexDigest(protocolName.getBytes());
        final Cursor protocolCursor = db.query(
                Protocols.TABLE_NAME,
                new String[] {
                    Protocols._ID
                },
                String.format(Protocols.WHERE_CLAUSE_PROTOCOL, protocolHashAsString),
                null, null, null, null, "1");

        try {
            if (!protocolCursor.moveToFirst()) {
                return -1;
            }
            return protocolCursor.getLong(0);
        } finally {
            protocolCursor.close();
        }
    }

    public Neighbor getNeighbor(long neighborId) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        final Cursor neighborCursor = db.query(
                NeighborProtocols.VIEW_NAME,
                NeighborProtocols.PROJECTION_DEFAULT,
                Neighbors._ID + " = ?",
                new String[] {
                    String.valueOf(neighborId)
                },
                null, null, null, "1");

        if (!neighborCursor.moveToFirst()) {
            throw new IllegalArgumentException("No neighbor with ID " + neighborId);
        }

        try {
            return Neighbor.fromCursor(neighborCursor);
        } finally {
            neighborCursor.close();
        }
    }

    public Cursor getNeighborsCursor(long timeLastSeen) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        final Cursor neighborsCursor = db.query(
                NeighborProtocols.VIEW_NAME,
                NeighborProtocols.PROJECTION_DEFAULT,
                String.format(NeighborProtocols.WHERE_CLAUSE_TIME, timeLastSeen),
                null, null, null, null);

        return neighborsCursor;
    }

    public Set<Neighbor> getNeighbors(long timeLastSeen) {
        Cursor neighborsCursor = getNeighborsCursor(timeLastSeen);

        final Set<Neighbor> neighbors = new HashSet<>();
        while (neighborsCursor.moveToNext()) {
            neighbors.add(Neighbor.fromCursor(neighborsCursor));
        }
        neighborsCursor.close();

        return neighbors;
    }

    public TransportPacket.Builder getPacket(long packetId) {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();

        final Cursor packetCursor = db.query(
                Packets.VIEW_NAME_ALL,
                Packets.PROJECTION_DEFAULT,
                Packets._ID + " = ?",
                new String[] {
                    String.valueOf(packetId)
                },
                null, null, null, "1");

        if (!packetCursor.moveToFirst()) {
            throw new IllegalArgumentException("No packet with ID " + packetId);
        }

        try {
            return TransportPacketFactory.fromCursor(packetCursor);
        } finally {
            packetCursor.close();
        }
    }

    public Cursor getOutgoingPackets() {
        final SQLiteDatabase db = mDbHelper.getReadableDatabase();
        final Cursor packetCursor = db.query(
                Packets.VIEW_NAME_OUTGOING,
                Packets.PROJECTION_DEFAULT,
                null, null, null, null,
                Packets.SORT_ORDER_DEFAULT);

        return packetCursor;
    }

    public String insertApplication(String packageName) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        final String appToken = TokenGenerator.generateToken(12);

        final ContentValues values = new ContentValues();
        values.put(Apps.COLUMN_PACKAGE_NAME, packageName);
        values.put(Apps.COLUMN_APP_TOKEN, appToken);

        db.insertOrThrow(Apps.TABLE_NAME, null, values);

        mContext.getContentResolver().notifyChange(Apps.URI_ALL, null);
        return appToken;
    }

    public long insertIdentity(String keyLabel, byte[] publicKey, String displayName) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final ContentValues values = new ContentValues();
        values.put(Identities.COLUMN_IDENTIFIER, keyLabel);
        values.put(Identities.COLUMN_PUBLICKEY, publicKey);

        String name = displayName;
        if (displayName == null || displayName.isEmpty()) {
            name = ByteUtils.bytesToHex(publicKey);
        }
        values.put(Identities.COLUMN_DISPLAY_NAME, name);

        try {
            return db.insert(Identities.TABLE_NAME, null, values);
        } finally {
            mContext.getContentResolver().notifyChange(Identities.URI_ALL, null);
        }
    }

    private long insertProtocol(
            String name, Boolean encrypted, Boolean authenticated, Integer defaultTtl) {
        final ContentValues values = new ContentValues();
        values.put(Protocols.COLUMN_IDENTIFIER, name);
        values.put(Protocols.COLUMN_IDENTIFIER_HASH,
                CryptoHelper.createDigest(name.getBytes()));

        if (encrypted != null) {
            values.put(Protocols.COLUMN_ENCRYPTED, encrypted);
        }
        if (authenticated != null) {
            values.put(Protocols.COLUMN_SIGNED, authenticated);
        }
        if (defaultTtl != null) {
            values.put(Protocols.COLUMN_DEFAULT_TTL, defaultTtl);
        }

        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        try {
            return db.insert(Protocols.TABLE_NAME, null, values);
        } finally {
            mContext.getContentResolver().notifyChange(Protocols.URI_ALL, null);
        }
    }

    private long insertRawImplementation(long appId, long protocolId, long identityId) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final ContentValues values = new ContentValues();
        values.put(Implementations.COLUMN_APP_ID, appId);
        values.put(Implementations.COLUMN_PROTOCOL_ID, protocolId);
        values.put(Implementations.COLUMN_IDENTITY_ID, identityId);
        values.put(Implementations.COLUMN_TOKEN, TokenGenerator.generateToken(16));

        // TODO: CONFLICT_IGNORE does not necessarily return the previous value
        // see https://code.google.com/p/android/issues/detail?id=13045
        return db.insertWithOnConflict(
                Implementations.TABLE_NAME, null, values,
                SQLiteDatabase.CONFLICT_IGNORE);
    }

    public Implementation insertImplementation(String appToken, Bundle values) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            final long appId = getApplicationIdByToken(appToken);
            if (appId > 0) {
                long protocolId = getProtocolIdByName(
                        values.getString(Protocols.COLUMN_IDENTIFIER));

                if (protocolId < 0) {
                    protocolId = insertProtocol(
                            values.getString(Protocols.COLUMN_IDENTIFIER),
                            values.getBoolean(Protocols.COLUMN_ENCRYPTED),
                            values.getBoolean(Protocols.COLUMN_SIGNED),
                            values.getInt(Protocols.COLUMN_DEFAULT_TTL));
                }

                if (protocolId > 0) {
                    final long implementationId = insertRawImplementation(appId, protocolId, 1);
                    if (implementationId > 0) {
                        db.setTransactionSuccessful();

                        mContext.getContentResolver()
                                .notifyChange(Protocols.URI_ALL, null);
                        mContext.getContentResolver()
                                .notifyChange(Implementations.URI_ALL, null);

                        return getImplementation(implementationId);
                    }
                }
            }
        } finally {
            db.endTransaction();
        }

        Log.e(TAG, "Error while inserting implementation!");
        return null;
    }

    public long insertIncomingPacket(TransportPacket packet, PacketQueues[] queues) {
        final ContentValues data = TransportPacketFactory.toContentValues(packet);

        final ProtocolRegistry protocolRegistry = ProtocolRegistry.getInstance(mContext);
        final Set<Implementation> implementations =
                protocolRegistry.getProtocolImplementations(packet.getProtocol().toByteArray());

        if (!implementations.isEmpty()) {
            // There is an app for this protocol
            final Implementation impl = implementations.iterator().next();

            // If the packet contains a MAC, then it has already been checked before. Here we
            // additionally make sure that there really was a MAC if the protocol requires it.
            if (impl.isSigned() && !packet.hasMac()) {
                Log.w(TAG, "Rejecting packet: Protocol "
                        + impl.getProtocolName() + " requires signed data.");
                return -1;
            }

            // Decrypt the payload if the packet is targeted at us
            if (impl.isEncrypted()) {
                Log.v(TAG, "Decrypting incoming packet... length: " + packet.getSerializedSize());
                final byte[] senderPublicKey = packet.getSourceNode().toByteArray();
                final byte[] plaintext;
                try {
                    plaintext = CryptoHelper.decrypt(
                            mContext, packet.getPayload().toByteArray(), senderPublicKey);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Rejecting packet: " + e.getLocalizedMessage());
                    return -1;
                }

                data.put(Packets.COLUMN_PAYLOAD, plaintext);
            }
        }

        final long rowId = insertPacket(data, queues);
        if (rowId > 0) {
            // Notify listeners that a packet arrived
            for (Implementation impl : implementations) {
                final Uri notifyUri =
                        OppNetContract.buildProtocolUri(Packets.URI_INCOMING, impl.getToken());
                mContext.getContentResolver().notifyChange(notifyUri, null);
                Log.v(TAG, "Notified URI " + notifyUri);
            }
        }
        return rowId;
    }

    public long insertOutgoingPacket(Implementation implementation, ContentValues data) {
        if (!data.containsKey(Packets.COLUMN_PAYLOAD)) {
            throw new IllegalArgumentException("Packet must contain payload data");
        }

        data.put(Packets.COLUMN_PROTOCOL, implementation.getProtocolHash());

        if (!data.containsKey(Packets.COLUMN_TTL)) {
            // let packet live for a day per default
            final long currentTime = System.currentTimeMillis() / 1000;
            data.put(Packets.COLUMN_TTL, currentTime + implementation.getDefaultTtl());
        }

        if (implementation.isEncrypted() || implementation.isSigned()) {
            // If packets are signed or encrypted, the source node field (= node's public key) is
            // mandatory. Additionally, the source node field must be set prior to signing the whole
            // packet.
            data.put(Packets.COLUMN_SOURCE_NODE, implementation.getIdentity());
        }

        final TransportPacket packet = TransportPacketFactory.unsignedFromContentValues(data);

        if (implementation.isSigned()) {
            final byte[] signature = CryptoHelper.sign(mContext, packet.toByteArray());
            data.put(Packets.COLUMN_MAC, signature);
        }

        data.put(Packets.COLUMN_ENCRYPTED, implementation.isEncrypted());
        data.put(Packets.COLUMN_PACKET_HASH,
                CryptoHelper.createDigest(packet.toByteArray()));

        return insertPacket(data, new PacketQueues[] {
                PacketQueues.OUTGOING
        });
    }

    private long insertPacket(ContentValues packet, PacketQueues[] queues) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            long rowId = 0;
            try {
                rowId = db.insertOrThrow(Packets.TABLE_NAME, null, packet);
            } catch (SQLiteConstraintException e) {
                // Packet already exists in database, skip adding it again
            }

            if (rowId > 0) {
                // Packet inserted successfully, now add it to the appropriate queues
                for (final PacketQueues queue : queues) {
                    final ContentValues values = new ContentValues();
                    values.put(PacketQueues.COLUMN_QUEUE, queue.ordinal());
                    values.put(PacketQueues.COLUMN_PACKET_ID, rowId);
                    db.insert(PacketQueues.TABLE_NAME, null, values);
                }

                db.setTransactionSuccessful();

                mContext.getContentResolver().notifyChange(Packets.URI_ALL, null);
                mContext.getContentResolver().notifyChange(Packets.URI_OUTGOING, null);
                return rowId;
            }
        } finally {
            db.endTransaction();
        }

        return 0;
    }

    public void insertNeighbor(ContentValues values, List<ByteString> protocols) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        final byte[] neighborId = values.getAsByteArray(Neighbors.COLUMN_IDENTIFIER);
        if (neighborId == null) {
            throw new IllegalArgumentException("Can not insert node with no node id!");
        }

        try {
            final long rawNeighborId = upsertRawNeighbor(neighborId, values);

            if (rawNeighborId > 0) {
                if (!protocols.isEmpty()) {
                    insertRemoteProtocols(rawNeighborId, protocols);
                }

                db.setTransactionSuccessful();

                mContext.getContentResolver().notifyChange(Neighbors.URI_ALL, null);
                mContext.getContentResolver().notifyChange(NeighborProtocols.URI_ALL, null);
                mContext.getContentResolver().notifyChange(NeighborProtocols.URI_CURRENT, null);
                mContext.getContentResolver().notifyChange(ProtocolNeighbors.URI_ITEM, null);
            }
        } finally {
            db.endTransaction();
        }
    }

    private long upsertRawNeighbor(byte[] neighborId, ContentValues values) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            final SQLiteStatement selectStmt = db.compileStatement(
                    "select " + Neighbors._ID
                            + " from " + Neighbors.TABLE_NAME
                            + " where " + Neighbors.COLUMN_IDENTIFIER + " = ?");
            selectStmt.bindBlob(1, neighborId);

            long neighborRowId;
            try {
                neighborRowId = selectStmt.simpleQueryForLong();
            } catch (SQLiteDoneException e) {
                neighborRowId = -1;
            }

            final long timeLastSeen = values.getAsLong(Neighbors.COLUMN_TIME_LASTSEEN);
            final boolean multicastCapable = values
                    .getAsBoolean(Neighbors.COLUMN_MULTICAST_CAPABLE);
            final int multicastCapableAsInt = (multicastCapable ? 1 : 0);
            final String networkName = values.getAsString(Neighbors.COLUMN_NETWORK);

            final byte[] ip4Address = values.getAsByteArray(Neighbors.COLUMN_IP4);
            assert (ip4Address == null || ip4Address.length == 4);
            final byte[] ip6Address = values.getAsByteArray(Neighbors.COLUMN_IP6);
            assert (ip6Address == null || ip6Address.length == 16);
            final byte[] btAddress = values.getAsByteArray(Neighbors.COLUMN_BLUETOOTH);
            assert (btAddress == null || btAddress.length == 6);

            boolean success = false;
            if (neighborRowId <= 0) {
                // Neighbor has never been seen before -> INSERT
                neighborRowId = insertRawNeighbor(
                        neighborId, timeLastSeen, multicastCapableAsInt,
                        networkName, ip4Address, ip6Address, btAddress);
                success = (neighborRowId > 0);
            } else {
                // Neighbor already registered -> UPDATE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                    success = updateRawNeighbor_postSDK11(
                            neighborRowId, timeLastSeen, multicastCapableAsInt,
                            networkName, ip4Address, ip6Address, btAddress);
                } else {
                    success = updateRawNeighbor_preSDK11(
                            neighborRowId, timeLastSeen, multicastCapableAsInt,
                            networkName, ip4Address, ip6Address, btAddress);
                }
            }

            if (success && neighborRowId > 0) {
                db.setTransactionSuccessful();
            }
            return neighborRowId;
        } finally {
            db.endTransaction();
        }
    }

    private long insertRawNeighbor(
            byte[] neighborId, long timeLastSeen, int multicastCapable,
            String networkName, byte[] ip4Address, byte[] ip6Address, byte[] btAddress) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final SQLiteStatement insertStmt = db.compileStatement(
                "insert into " + Neighbors.TABLE_NAME + " ("
                        + Neighbors.COLUMN_IDENTIFIER + ", "
                        + Neighbors.COLUMN_TIME_LASTSEEN + ", "
                        + Neighbors.COLUMN_MULTICAST_CAPABLE + ", "
                        + Neighbors.COLUMN_NETWORK + ", "
                        + Neighbors.COLUMN_IP4 + ", "
                        + Neighbors.COLUMN_IP6 + ", "
                        + Neighbors.COLUMN_BLUETOOTH
                        + ") values (?, ?, ?, ?, ?, ?, ?)");

        insertStmt.bindBlob(1, neighborId);
        insertStmt.bindLong(2, timeLastSeen);
        insertStmt.bindLong(3, multicastCapable);

        if (networkName == null) {
            insertStmt.bindNull(4);
        } else {
            insertStmt.bindString(4, networkName);
        }

        if (ip4Address == null) {
            insertStmt.bindNull(5);
        } else {
            insertStmt.bindBlob(5, ip4Address);
        }

        if (ip6Address == null) {
            insertStmt.bindNull(6);
        } else {
            insertStmt.bindBlob(6, ip6Address);
        }

        if (btAddress == null) {
            insertStmt.bindNull(7);
        } else {
            insertStmt.bindBlob(7, btAddress);
        }

        return insertStmt.executeInsert();
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private boolean updateRawNeighbor_postSDK11(
            long neighborRowId, long timeLastSeen, int multicastCapable,
            String networkName, byte[] ip4Address, byte[] ip6Address, byte[] btAddress) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final SQLiteStatement updateStmt = db.compileStatement(
                "update " + Neighbors.TABLE_NAME + " set "
                        + Neighbors.COLUMN_TIME_LASTSEEN + " = ?,"
                        + Neighbors.COLUMN_MULTICAST_CAPABLE + " = ?,"
                        + Neighbors.COLUMN_NETWORK + " = ?,"
                        + Neighbors.COLUMN_IP4 + " = ?,"
                        + Neighbors.COLUMN_IP6 + " = ?,"
                        + Neighbors.COLUMN_BLUETOOTH + " = ?"
                        + " where " + Neighbors._ID + " = ?"
                        + " and " + Neighbors.COLUMN_TIME_LASTSEEN + " < ?");

        // Bind updated values
        updateStmt.bindLong(1, timeLastSeen);
        updateStmt.bindLong(2, multicastCapable);

        if (networkName == null) {
            updateStmt.bindNull(3);
        } else {
            updateStmt.bindString(3, networkName);
        }

        if (ip4Address == null) {
            updateStmt.bindNull(4);
        } else {
            updateStmt.bindBlob(4, ip4Address);
        }

        if (ip6Address == null) {
            updateStmt.bindNull(5);
        } else {
            updateStmt.bindBlob(5, ip6Address);
        }

        if (btAddress == null) {
            updateStmt.bindNull(6);
        } else {
            updateStmt.bindBlob(6, btAddress);
        }

        // Bind values for WHERE clause
        updateStmt.bindLong(7, neighborRowId);
        updateStmt.bindLong(8, timeLastSeen);

        return (updateStmt.executeUpdateDelete() > 0);
    }

    private boolean updateRawNeighbor_preSDK11(
            long neighborRowId, long timeLastSeen, int multicastCapable,
            String networkName, byte[] ip4Address, byte[] ip6Address, byte[] btAddress) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();

        final String networkOrNull = (networkName == null) ? "null" : "'" + networkName + "'";
        final StringBuilder updateQueryString =
                new StringBuilder("update " + Neighbors.TABLE_NAME + " set ")
                        .append(Neighbors.COLUMN_TIME_LASTSEEN + " = ")
                        .append(timeLastSeen)
                        .append(", " + Neighbors.COLUMN_MULTICAST_CAPABLE + " = ")
                        .append(multicastCapable)
                        .append(", " + Neighbors.COLUMN_NETWORK + " = ")
                        .append(networkOrNull);

        // Append other NULLable columns
        updateQueryString.append(", " + Neighbors.COLUMN_IP4 + " = ");
        if (ip4Address == null) {
            updateQueryString.append("null");
        } else {
            updateQueryString.append("X'").append(ByteUtils.bytesToHex(ip4Address)).append("'");
        }

        updateQueryString.append(", " + Neighbors.COLUMN_IP6 + " = ");
        if (ip6Address == null) {
            updateQueryString.append("null");
        } else {
            updateQueryString.append("X'").append(ByteUtils.bytesToHex(ip6Address)).append("'");
        }

        updateQueryString.append(", " + Neighbors.COLUMN_BLUETOOTH + " = ");
        if (btAddress == null) {
            updateQueryString.append("null");
        } else {
            updateQueryString.append("X'").append(ByteUtils.bytesToHex(btAddress)).append("'");
        }

        // Add WHERE clause
        updateQueryString.append(" where " + Neighbors._ID + " = ").append(neighborRowId)
                .append(" and " + Neighbors.COLUMN_TIME_LASTSEEN + " < ").append(timeLastSeen);

        db.execSQL(updateQueryString.toString());
        return true;
    }

    private void insertRemoteProtocols(long neighborRowId, List<ByteString> protocols) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            // Remove previously stored RemoteProtocols
            db.delete(
                    RemoteProtocols.TABLE_NAME,
                    RemoteProtocols.COLUMN_NEIGHBOR_ID + " = " + neighborRowId,
                    null);

            // Insert each protocol, reusing the same prepared statement for speed.
            final SQLiteStatement insertStmt = db.compileStatement(
                    "insert into " + RemoteProtocols.TABLE_NAME + " values (?, ?, ?)");
            for (final ByteString protocol : protocols) {
                if (protocol.size() != 20) {
                    Log.w(TAG, "Protocol hash must be of length 20, not " + protocol.size());
                    continue;
                }

                insertStmt.clearBindings();
                insertStmt.bindNull(1);
                insertStmt.bindLong(2, neighborRowId);
                insertStmt.bindBlob(3, protocol.toByteArray());

                final long rowId = insertStmt.executeInsert();
                if (rowId < 0) {
                    Log.w(TAG, String.format(
                            "Error while inserting RemoteProtocol %s for neighbor %d",
                            ByteUtils.bytesToHex(protocol), neighborRowId));
                }
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public int deleteApplication(String appToken) {
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.beginTransaction();

        try {
            int deletedProtocolRows = 0;
            final Cursor implementations = getImplementations(
                    Apps.COLUMN_APP_TOKEN + " = ?", new String[] {
                        appToken
                    });

            if (implementations.moveToFirst()) {
                final long appId = implementations.getLong(
                        implementations.getColumnIndex(Implementations.COLUMN_APP_ID));

                final int protocolColumnId =
                        implementations
                                .getColumnIndex(Implementations.COLUMN_PROTOCOL_ID);
                final HashSet<Long> possiblyAffectedProtocols = new HashSet<>();

                while (!implementations.isAfterLast()) {
                    possiblyAffectedProtocols.add(implementations.getLong(protocolColumnId));
                    implementations.moveToNext();
                }

                final String sqlInClause =
                        Implementations.COLUMN_PROTOCOL_ID
                                + " in ("
                                + TextUtils.join(", ", possiblyAffectedProtocols.toArray())
                                + ")"
                                + " and " + Implementations.COLUMN_APP_ID + " != "
                                + appId;

                Cursor protocols = db.query(
                        Implementations.TABLE_NAME,
                        new String[] {
                            Implementations.COLUMN_PROTOCOL_ID
                        },
                        sqlInClause, null,
                        Implementations.COLUMN_PROTOCOL_ID, null, null);

                final HashSet<Long> notAffectedProtocols = new HashSet<>();
                while (protocols.moveToNext()) {
                    notAffectedProtocols.add(protocols.getLong(0));
                }

                possiblyAffectedProtocols.removeAll(notAffectedProtocols);
                if (!possiblyAffectedProtocols.isEmpty()) {
                    deletedProtocolRows = db.delete(
                            Protocols.TABLE_NAME,
                            Protocols._ID + " in ("
                                    + TextUtils.join(", ", possiblyAffectedProtocols) + ")",
                            null);
                    mContext.getContentResolver()
                            .notifyChange(Protocols.URI_ALL, null);
                }
                mContext.getContentResolver()
                        .notifyChange(Implementations.URI_ALL, null);
            }

            final int deletedAppRows = db.delete(Apps.TABLE_NAME,
                    Apps.COLUMN_APP_TOKEN + " = ?",
                    new String[] {
                        appToken
                    });
            mContext.getContentResolver().notifyChange(Apps.URI_ALL, null);
            mContext.getContentResolver().notifyChange(Packets.URI_ALL, null);

            db.setTransactionSuccessful();
            return deletedAppRows + deletedProtocolRows;
        } finally {
            db.endTransaction();
        }
    }

    public int deleteExpiredPackets(long expirationTimestamp) {
        // TODO: clean up queues for android < 4.0 (e.g. select first, then batch-delete)
        final SQLiteDatabase db = mDbHelper.getWritableDatabase();
        try {
            return db.delete(Packets.TABLE_NAME,
                    Packets.WHERE_CLAUSE_EXPIRED,
                    new String[] {
                        String.valueOf(expirationTimestamp)
                    });
        } finally {
            mContext.getContentResolver().notifyChange(Packets.URI_ALL, null);
        }
    }
}
