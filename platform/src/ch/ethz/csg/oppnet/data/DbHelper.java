
package ch.ethz.csg.oppnet.data;

import android.annotation.TargetApi;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Build;

public class DbHelper extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 1;
    protected static final String DATABASE_NAME = "oppnet";

    private static DbHelper sInstance;

    private DbHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static synchronized DbHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new DbHelper(context);
        }
        return sInstance;
    }

    public static synchronized void resetDatabase(Context context) {
        final DbHelper dbh = DbHelper.getInstance(context);
        dbh.close();
        context.deleteDatabase(DbHelper.DATABASE_NAME);
        dbh.getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.beginTransaction();

        try {
            // create Identities table
            db.execSQL(FullContract.Identities.SQL_CREATE_TABLE);

            // create Apps table
            db.execSQL(FullContract.Apps.SQL_CREATE_TABLE);

            // create Protocols table
            db.execSQL(FullContract.Protocols.SQL_CREATE_TABLE);

            // create Implementations table
            db.execSQL(FullContract.Implementations.SQL_CREATE_TABLE);
            db.execSQL(FullContract.Implementations.SQL_CREATE_VIEW_FULL_DETAILS);

            // create Neighbors and RemoteProtocols table + support views
            db.execSQL(FullContract.Neighbors.SQL_CREATE_TABLE);
            db.execSQL(FullContract.RemoteProtocols.SQL_CREATE_TABLE);
            db.execSQL(FullContract.RemoteProtocols.SQL_CREATE_INDEX);
            db.execSQL(FullContract.NeighborProtocols.SQL_CREATE_VIEW);
            db.execSQL(FullContract.ProtocolNeighbors.SQL_CREATE_VIEW);

            // create Packets and PacketQueues tables + views
            db.execSQL(FullContract.Packets.SQL_CREATE_TABLE);
            db.execSQL(FullContract.Packets.SQL_CREATE_ASSOCIATION_TABLE);
            db.execSQL(FullContract.Packets.SQL_CREATE_VIEW_ALL_PACKETS);
            db.execSQL(FullContract.Packets.SQL_CREATE_VIEW_INCOMING);
            db.execSQL(FullContract.Packets.SQL_CREATE_VIEW_OUTGOING);

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // behavior not defined so far
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    /**
     * Builds a FOREIGN KEY constraint for the given column with CASCADE behavior.
     * 
     * @param columnName The name of the column on which the FOREIGN KEY constraint is placed.
     * @param referenceTable The name of the target table for the FOREIGN KEY.
     * @param referenceColumn The name of the column in the target table for the FOREIGN KEY.
     * @return A string containing a valid FOREIGN KEY constraint.
     */
    public static String buildForeignKeyConstraint(
            String columnName, String referenceTable, String referenceColumn) {
        final String tpl = "foreign key(%s) references %s(%s) on delete cascade on update cascade";
        return String.format(tpl, columnName, referenceTable, referenceColumn);
    }

    /**
     * Builds a CHECK constraint for the given column, taking values from an enum.
     * <p>
     * The assumption is that all enum values represent continuous integers. This function will use
     * the ordinal() method of each enum value.
     * 
     * @param columnName The name of the column on which the CHECK constraint is placed.
     * @param source The class of the enum which holds all possible values.
     * @return A string containing a valid CHECK constraint.
     */
    public static String buildCheckConstraint(String columnName, Class<? extends Enum<?>> source) {
        StringBuilder constraint = new StringBuilder("check(").append(columnName).append(" in ('");
        for (Enum<?> value : source.getEnumConstants()) {
            constraint.append(value.ordinal()).append("', '");
        }
        return constraint.append("'))").toString();
    }

    public static String buildBinaryWhere(String columnName) {
        return columnName + " = X'%s'";
    }
}
