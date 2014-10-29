
package ch.ethz.csg.oppnet.example.chat.data;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteDatabase;
import android.provider.BaseColumns;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public class ChatMessage {
    public String sender;
    public String content;
    public long timeSent;
    public long timeReceived;
    private String mHash;

    public String getHash() {
        if (mHash == null) {
            String raw = String.format(Locale.US, "%s|%s|%d", sender, content, timeSent);
            try {
                mHash = createHash(raw);
            } catch (NoSuchAlgorithmException e) {
                mHash = null;
            }
        }
        return mHash;
    }

    public static String createHash(String input) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA1");
        md.update(input.getBytes());
        byte[] digest = md.digest();

        StringBuilder sb = new StringBuilder(2 * digest.length);
        for (byte b : digest) {
            sb.append("0123456789ABCDEF".charAt((b & 0xF0) >> 4));
            sb.append("0123456789ABCDEF".charAt((b & 0x0F)));
        }
        return sb.toString();
    }

    /* Messages table definition */
    public static abstract class Store implements BaseColumns {
        public static final String TABLE_NAME = "messages";

        public static final String COLUMN_SENDER = "sender";
        public static final String COLUMN_CONTENT = "content";
        public static final String COLUMN_TIME_SENT = "time_sent";
        public static final String COLUMN_TIME_RECEIVED = "time_received";
        public static final String COLUMN_HASH = "hash";

        /* SQL statements */
        public static final String SQL_CREATE_TABLE =
                "CREATE TABLE " + TABLE_NAME + " ("
                        + _ID + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + COLUMN_SENDER + " TEXT NOT NULL, "
                        + COLUMN_CONTENT + " TEXT NOT NULL, "
                        + COLUMN_TIME_SENT + " INTEGER NOT NULL, "
                        + COLUMN_TIME_RECEIVED + " INTEGER NOT NULL, "
                        + COLUMN_HASH + " TEXT UNIQUE NOT NULL)";

        /* Query methods */
        public static Cursor fetchAllMessages(SQLiteDatabase db) {
            String[] columns = new String[] {
                    _ID, COLUMN_SENDER, COLUMN_CONTENT, COLUMN_TIME_SENT, COLUMN_TIME_RECEIVED
            };
            return db.query(TABLE_NAME, columns, null, null, null, null, COLUMN_TIME_RECEIVED);
        }

        public static int discardAllMessages(SQLiteDatabase db) {
            return db.delete(TABLE_NAME, null, null);
        }

        public static long addMessage(SQLiteDatabase db, ChatMessage msg) {
            ContentValues values = new ContentValues();
            values.put(COLUMN_SENDER, msg.sender);
            values.put(COLUMN_CONTENT, msg.content);
            values.put(COLUMN_TIME_SENT, msg.timeSent);
            values.put(COLUMN_TIME_RECEIVED, msg.timeReceived);
            values.put(COLUMN_HASH, msg.getHash());

            long messageId = -1;
            try {
                messageId = db.insertOrThrow(TABLE_NAME, null, values);
            } catch (SQLiteConstraintException e) {
                // duplicate
            }
            return messageId;
        }
    }
}
