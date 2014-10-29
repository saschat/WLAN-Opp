
package ch.ethz.csg.oppnet.example.chat.ui;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import ch.ethz.csg.oppnet.example.chat.R;
import ch.ethz.csg.oppnet.example.chat.data.ChatMessage;
import ch.ethz.csg.oppnet.example.chat.data.DatabaseHelper;
import ch.ethz.csg.oppnet.lib.data.ExchangePacket;
import ch.ethz.csg.oppnet.lib.data.ExchangePacketObserver.NewPacketCallback;
import ch.ethz.csg.oppnet.lib.ipc.OppNetConnector;

import java.util.Locale;

public class MainActivity extends Activity implements NewPacketCallback {
    private OppNetConnector mConnector;
    private SQLiteDatabase mDb;
    private ListView mMessagesList;
    private SimpleCursorAdapter mCursorAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        DatabaseHelper dbHelper = new DatabaseHelper(this);
        mDb = dbHelper.getWritableDatabase();

        Cursor cursor = ChatMessage.Store.fetchAllMessages(mDb);
        String[] cols = new String[] {
                ChatMessage.Store.COLUMN_CONTENT, ChatMessage.Store.COLUMN_SENDER
        };
        int[] to = new int[] {
                R.id.messageContent, R.id.messageMeta
        };

        mCursorAdapter = new SimpleCursorAdapter(this, R.layout.message, cursor, cols, to, 0);
        mCursorAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
            @Override
            public boolean setViewValue(View v, Cursor cursor, int columnIndex) {
                TextView view = (TextView) v;
                String content = cursor.getString(columnIndex);

                if (cursor.getColumnName(columnIndex).equals(ChatMessage.Store.COLUMN_SENDER)) {
                    int timeColIndex = cursor.getColumnIndex(ChatMessage.Store.COLUMN_TIME_SENT);
                    int flags = DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_SHOW_DATE
                            | DateUtils.FORMAT_SHOW_YEAR
                            | DateUtils.FORMAT_NUMERIC_DATE;

                    String formatted = DateUtils.formatDateTime(
                            MainActivity.this, 1000 * cursor.getLong(timeColIndex), flags);
                    content = content + " at " + formatted;
                }

                view.setText(content);
                return true;
            }
        });

        mMessagesList = (ListView) findViewById(R.id.messagesList);
        mMessagesList.setAdapter(mCursorAdapter);

        mConnector = OppNetConnector.getInstance(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mConnector.bind()) {
            mConnector.registerProtocolsFromResources(R.xml.protocols, this);
        }
    }

    @Override
    protected void onPause() {
        mConnector.unbind();
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_discard:
                ChatMessage.Store.discardAllMessages(mDb);
                refreshCursor();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public void sendMessage(View view) {
        EditText inputField = (EditText) findViewById(R.id.input_newMessage);
        String message = inputField.getText().toString().trim();

        if (!message.isEmpty()) {
            ChatMessage newMsg = new ChatMessage();
            newMsg.sender = "Me Myself and I";
            newMsg.content = message;

            long currentTime = System.currentTimeMillis() / 1000L;
            newMsg.timeSent = currentTime;
            newMsg.timeReceived = currentTime;

            ChatMessage.Store.addMessage(mDb, newMsg);
            mConnector.enqueuePacket(message.getBytes());
            mConnector.requestDiscovery();
            refreshCursor();
        }

        inputField.setText(null);
    }

    private void refreshCursor() {
        Cursor newCursor = ChatMessage.Store.fetchAllMessages(mDb);
        mCursorAdapter.changeCursor(newCursor);
    }

    @Override
    public void onExchangePacketReceived(ExchangePacket packet) {
        ChatMessage newMsg = new ChatMessage();
        newMsg.content = new String(packet.getPayload());
        newMsg.timeSent = packet.getTimeReceived();
        newMsg.timeReceived = packet.getTimeReceived();

        final String sender = packet.getSourceNodeAsHex();
        newMsg.sender = 
                sender != null ? sender.substring(0, 12).toLowerCase(Locale.US) : "Anonymous";

        ChatMessage.Store.addMessage(mDb, newMsg);
        refreshCursor();
    }
}
