
package ch.ethz.csg.oppnet.lib.data;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import ch.ethz.csg.oppnet.lib.data.OppNetContract.Packets;

import com.google.common.base.Objects;

import java.util.ArrayList;
import java.util.List;

public class ExchangePacketObserver extends ContentObserver {
    private final Context mContext;
    private final Uri mObservedUri;
    private final NewPacketCallback mCallback;
    private long mTimeLastPacketReceived;

    public ExchangePacketObserver(
            Context context, Handler handler, String protocolToken, NewPacketCallback callback) {
        super(handler);

        mContext = context;
        mObservedUri = OppNetContract.buildProtocolUri(Packets.URI_INCOMING, protocolToken);
        mCallback = callback;
        mTimeLastPacketReceived = TokenStore.getLastPacketReceived(mContext);
    }

    public void register() {
        mContext.getContentResolver().registerContentObserver(mObservedUri, true, this);
    }

    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
    }

    // HELPER METHODS
    public List<ExchangePacket> getPacketsSince(long timestamp) {
        final ArrayList<ExchangePacket> newPackets = new ArrayList<>();

        final Cursor newPacketsCursor = mContext.getContentResolver().query(
                mObservedUri,
                null,
                Packets.COLUMN_TIME_RECEIVED + " > ?",
                new String[] {
                    String.valueOf(timestamp)
                },
                null);

        while (newPacketsCursor.moveToNext()) {
            newPackets.add(ExchangePacket.fromCursor(newPacketsCursor));
        }
        return newPackets;
    }

    // CONTENT OBSERVER CONTRACT
    @Override
    public void onChange(boolean selfChange) {
        onChange(selfChange, null);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        for (ExchangePacket packet : getPacketsSince(mTimeLastPacketReceived)) {
            mTimeLastPacketReceived = Math.max(mTimeLastPacketReceived, packet.getTimeReceived());
            mCallback.onExchangePacketReceived(packet);
        }
        TokenStore.saveLastPacketReceived(mContext, mTimeLastPacketReceived);
    }

    // OBJECT CONTRACT
    @Override
    public int hashCode() {
        return Objects.hashCode(mObservedUri.toString());
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("uri", mObservedUri)
                .toString();
    }

    // CALLBACK INTERFACE
    public static interface NewPacketCallback {
        public void onExchangePacketReceived(ExchangePacket packet);
    }
}
