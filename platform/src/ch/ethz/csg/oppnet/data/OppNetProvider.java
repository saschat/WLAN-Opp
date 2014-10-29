
package ch.ethz.csg.oppnet.data;

import android.annotation.SuppressLint;
import android.content.ContentProvider;
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import ch.ethz.csg.oppnet.beaconing.BeaconingManager;
import ch.ethz.csg.oppnet.data.FullContract.Apps;
import ch.ethz.csg.oppnet.data.FullContract.Identities;
import ch.ethz.csg.oppnet.data.FullContract.Implementations;
import ch.ethz.csg.oppnet.data.FullContract.NeighborProtocols;
import ch.ethz.csg.oppnet.data.FullContract.Neighbors;
import ch.ethz.csg.oppnet.data.FullContract.Packets;
import ch.ethz.csg.oppnet.data.FullContract.ProtocolNeighbors;
import ch.ethz.csg.oppnet.data.FullContract.Protocols;
import ch.ethz.csg.oppnet.exchange.PacketRegistry;

import java.util.Locale;

public class OppNetProvider extends ContentProvider {
    private static final String TAG = OppNetProvider.class.getSimpleName();

    /**
     * All URI paths which can be handled by the content provider.
     * 
     * @author brunf
     */
    private static enum UriMatch {
        NO_MATCH,

        IDENTITY_LIST(Identities.URI_ALL, Identities.CONTENT_DIR_TYPE),
        IDENTITY_ID(Identities.URI_ALL, "/#", Identities.CONTENT_ITEM_TYPE),

        APP_LIST(Apps.URI_ALL, Apps.CONTENT_DIR_TYPE),
        APP_ID(Apps.URI_ALL, "/#", Apps.CONTENT_ITEM_TYPE),

        PROTOCOL_LIST(Protocols.URI_ALL, Protocols.CONTENT_DIR_TYPE),
        PROTOCOL_ID(Protocols.URI_ALL, "/#", Protocols.CONTENT_ITEM_TYPE),

        IMPLEMENTATION_LIST(Implementations.URI_ALL, Implementations.CONTENT_DIR_TYPE),
        IMPLEMENTATION_ID(Implementations.URI_ALL, "/#", Implementations.CONTENT_ITEM_TYPE),

        NEIGHBOR_LIST(Neighbors.URI_ALL, Neighbors.CONTENT_DIR_TYPE),
        NEIGHBOR_LIST_CURRENT(Neighbors.URI_CURRENT, Neighbors.CONTENT_DIR_TYPE),
        NEIGHBOR_LIST_RECENT(Neighbors.URI_RECENT, Neighbors.CONTENT_DIR_TYPE),
        NEIGHBOR_ID(Neighbors.URI_ALL, "/#", Neighbors.CONTENT_ITEM_TYPE),

        NEIGHBOR_PROTOCOLS_LIST(NeighborProtocols.URI_ALL, NeighborProtocols.CONTENT_DIR_TYPE),
        NEIGHBOR_PROTOCOLS_LIST_CURRENT(
                NeighborProtocols.URI_CURRENT, NeighborProtocols.CONTENT_DIR_TYPE),
        NEIGHBOR_PROTOCOLS_LIST_RECENT(
                NeighborProtocols.URI_RECENT, NeighborProtocols.CONTENT_DIR_TYPE),
        NEIGHBOR_PROTOCOLS_ID(NeighborProtocols.URI_ALL, "/#", NeighborProtocols.CONTENT_ITEM_TYPE),

        PROTOCOL_NEIGHBORS(ProtocolNeighbors.URI_ITEM, "/*", ProtocolNeighbors.CONTENT_ITEM_TYPE),

        PACKET_LIST(Packets.URI_ALL, Packets.CONTENT_DIR_TYPE),
        PACKET_LIST_INCOMING(Packets.URI_INCOMING, Packets.CONTENT_DIR_TYPE),
        PACKET_LIST_OUTGOING(Packets.URI_OUTGOING, Packets.CONTENT_DIR_TYPE),
        PACKET_ID(Packets.URI_ALL, "/#", Packets.CONTENT_ITEM_TYPE);

        private final Uri mUri;
        private final String mMatchedPath;
        private final String mContentType;

        private UriMatch() {
            mUri = null;
            mMatchedPath = null;
            mContentType = null;
        }

        private UriMatch(Uri uri, String contentType) {
            mUri = uri;
            mMatchedPath = TextUtils.join("/", uri.getPathSegments());
            mContentType = contentType;
        }

        private UriMatch(Uri uri, String suffix, String contentType) {
            mUri = uri;
            mMatchedPath = TextUtils.join("/", uri.getPathSegments()) + suffix;
            mContentType = contentType;
        }

        public Uri getUri() {
            return mUri;
        }

        public String getMatchedPath() {
            return mMatchedPath;
        }

        public String getContentType() {
            return mContentType;
        }
    }

    public static final UriMatcher sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        for (UriMatch match : UriMatch.values()) {
            sUriMatcher.addURI(FullContract.AUTHORITY, match.getMatchedPath(), match.ordinal());
        }
    }

    private DbController mDbController;
    private static DbHelper sDbHelper;

    @Override
    public boolean onCreate() {
        mDbController = new DbController(getContext());
        sDbHelper = DbHelper.getInstance(getContext());
        return true;
    }

    private UriMatch getSafeUriMatch(Uri uri) {
        int match_ = sUriMatcher.match(uri);
        int match = Math.max(0, match_);
        return UriMatch.values()[match];
    }

    private Implementation resolveImplementationDetails(Uri uri) {
        String accessToken = uri.getQueryParameter(FullContract.ACCESS_TOKEN_PARAMETER_NAME);
        if (accessToken == null) {
            Log.v(TAG, "No access token in URI");
            return null;
            // throw new SecurityException(
            // "Required access token not found in URI " + uri);
        }

        Implementation implementation = mDbController.getImplementation(accessToken);
        if (implementation == null) {
            throw new SecurityException(
                    "Invalid access token in request for URI " + uri);
        }
        return implementation;
    }

    @Override
    public String getType(Uri uri) {
        UriMatch match = getSafeUriMatch(uri);
        return match.getContentType();
    }

    @SuppressLint("NewApi")
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        UriMatch match = getSafeUriMatch(uri);
        if (match.equals(UriMatch.NO_MATCH)) {
            Log.v(TAG, "No match for URI: " + uri);
            return null;
        }

        SQLiteDatabase db = sDbHelper.getReadableDatabase();
        String table = null;
        String where = null;
        SQLiteQueryBuilder queryBuilder = new SQLiteQueryBuilder();

        switch (match) {
            case APP_LIST: {
                table = FullContract.Apps.TABLE_NAME;

                if (projection == null) {
                    projection = FullContract.Apps.PROJECTION_DEFAULT;
                }

                if (sortOrder == null) {
                    sortOrder = FullContract.Apps.SORT_ORDER_DEFAULT;
                }
                break;
            }

            case PROTOCOL_LIST: {
                table = FullContract.Protocols.TABLE_NAME;

                if (projection == null) {
                    projection = FullContract.Protocols.PROJECTION_DEFAULT;
                }

                if (sortOrder == null) {
                    sortOrder = FullContract.Protocols.SORT_ORDER_DEFAULT;
                }
                break;
            }

            case NEIGHBOR_ID:
            case NEIGHBOR_LIST:
            case NEIGHBOR_LIST_CURRENT:
            case NEIGHBOR_LIST_RECENT: {
                table = Neighbors.TABLE_NAME;
                projection = Neighbors.PROJECTION_DEFAULT;

                if (match == UriMatch.NEIGHBOR_ID) {
                    // The query is for a single neighbor, so filter only this one.
                    where = String.format(Neighbors.WHERE_CLAUSE_ITEM,
                            uri.getEncodedFragment());
                } else {
                    // The query is for a list of neighbors, so we apply the default sort order.
                    sortOrder = Neighbors.SORT_ORDER_DEFAULT;

                    // Also we may need to filter the list.
                    if (match == UriMatch.NEIGHBOR_LIST_CURRENT) {
                        where = String.format(Locale.US, Neighbors.WHERE_CLAUSE_TIME,
                                BeaconingManager.getCurrentTimestamp());
                    } else if (match == UriMatch.NEIGHBOR_LIST_RECENT) {
                        where = String.format(Locale.US, Neighbors.WHERE_CLAUSE_TIME,
                                BeaconingManager.getRecentTimestamp());
                    }
                }
                break;
            }

            case NEIGHBOR_PROTOCOLS_ID:
            case NEIGHBOR_PROTOCOLS_LIST:
            case NEIGHBOR_PROTOCOLS_LIST_CURRENT:
            case NEIGHBOR_PROTOCOLS_LIST_RECENT: {
                table = NeighborProtocols.VIEW_NAME;
                projection = NeighborProtocols.PROJECTION_DEFAULT;

                if (match == UriMatch.NEIGHBOR_PROTOCOLS_ID) {
                    // The query is for a single neighbor, so filter only this one.
                    where = String.format(NeighborProtocols.WHERE_CLAUSE_NEIGHBOR_ID,
                            uri.getEncodedFragment());
                } else {
                    // The query is for a list of neighbors.
                    sortOrder = Neighbors.SORT_ORDER_DEFAULT;

                    if (match == UriMatch.NEIGHBOR_PROTOCOLS_LIST_CURRENT) {
                        where = String.format(Locale.US, NeighborProtocols.WHERE_CLAUSE_TIME,
                                BeaconingManager.getCurrentTimestamp());
                    } else if (match == UriMatch.NEIGHBOR_PROTOCOLS_LIST_RECENT) {
                        where = String.format(Locale.US, NeighborProtocols.WHERE_CLAUSE_TIME,
                                BeaconingManager.getRecentTimestamp());
                    }
                }
                break;
            }

            case PROTOCOL_NEIGHBORS: {
                table = FullContract.ProtocolNeighbors.VIEW_NAME;
                projection = FullContract.ProtocolNeighbors.PROJECTION_DEFAULT;
                sortOrder = FullContract.ProtocolNeighbors.SORT_ORDER_DEFAULT;

                final String protocolHashAsHex = uri.getLastPathSegment();
                if (TextUtils.isEmpty(protocolHashAsHex)) {
                    Log.v(TAG, "Received request for PROTOCOL_NEIGHBORS with empty protocol hash");
                    return null;
                }

                final String filter = uri.getQueryParameter(
                        FullContract.ProtocolNeighbors.QUERY_PARAM_FILTER_TIME);

                long filterTime;
                switch (filter) {
                    case "current": {
                        filterTime = BeaconingManager.getCurrentTimestamp();
                        break;
                    }

                    case "all":
                    default: {
                        filterTime = 0;
                    }
                }

                where = String.format(
                        FullContract.ProtocolNeighbors.WHERE_CLAUSE_PROTOCOL_AND_TIME,
                        protocolHashAsHex, filterTime);
                break;
            }

            case PACKET_LIST_INCOMING: {
                Implementation implementation = resolveImplementationDetails(uri);

                table = FullContract.Packets.VIEW_NAME_INCOMING;
                projection = FullContract.Packets.PROJECTION_DEFAULT_INCOMING;
                where = String.format(FullContract.Packets.WHERE_CLAUSE_PROTOCOL,
                        implementation.getProtocolHashAsHex());

                if (match != UriMatch.PACKET_ID) {
                    sortOrder = FullContract.Packets.SORT_ORDER_DEFAULT_INCOMING;
                }
                break;
            }

            case PACKET_LIST: {
                table = FullContract.Packets.VIEW_NAME_ALL;
                projection = FullContract.Packets.PROJECTION_DEFAULT;
                sortOrder = FullContract.Packets.SORT_ORDER_DEFAULT;
                break;
            }

            default: {
                // Something which should not be queried remotely.
                return null;
            }
        }

        queryBuilder.setTables(table);
        if (where != null) {
            // TODO: revert to some form of escaped query (-> SQL injections!)
            queryBuilder.appendWhere(where);
        }

        final Cursor result = queryBuilder.query(
                db, projection, selection, selectionArgs, null, null, sortOrder);
        result.setNotificationUri(getContext().getContentResolver(), uri);
        return result;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        UriMatch match = getSafeUriMatch(uri);
        if (match.equals(UriMatch.NO_MATCH)) {
            return null;
        }
        Implementation implementation = resolveImplementationDetails(uri);

        long recordId = 0;
        Uri baseUri = match.getUri();
        switch (match) {
            case PACKET_LIST_OUTGOING: {
                final PacketRegistry registry = PacketRegistry.getInstance(getContext());
                recordId = registry.registerOutgoingPacket(implementation, values);
                break;
            }

            default:
                // won't handle this INSERT request
                return null;
        }

        if (baseUri == null || recordId <= 0) {
            throw new SQLException(
                    "Problem while inserting into URI " + uri);
        }
        return ContentUris.withAppendedId(baseUri, recordId);
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        // No updates allowed
        return 0;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // No deletions allowed
        return 0;
    }

    public static OppNetProvider getLocalContentProvider(Context context) {
        final ContentProviderClient client = 
                context.getContentResolver().acquireContentProviderClient(FullContract.AUTHORITY);

        return (OppNetProvider) client.getLocalContentProvider();
    }
}
