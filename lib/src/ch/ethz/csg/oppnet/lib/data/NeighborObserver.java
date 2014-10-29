
package ch.ethz.csg.oppnet.lib.data;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import ch.ethz.csg.oppnet.lib.data.OppNetContract.NeighborProtocols;

import com.google.common.collect.Sets;

import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * This class can be used to keep track of current WLAN-Opp neighbors. It also takes an
 * {@link NeighborChangeCallback} to be performed each time the neighbors change.
 */
public class NeighborObserver extends ContentObserver {
    /**
     * Interface for tasks that can be executed by the {@link NeighborObserver}.
     */
    public static interface NeighborChangeCallback {
        public void onNeighborConnected(Neighbor currentNeighbor);

        public void onNeighborDisconnected(Neighbor recentNeighbor);

        public void onNeighborsChanged(Set<Neighbor> currentNeighbors);
    }

    public static class SimpleNeighborChangeCallback implements NeighborChangeCallback {
        @Override
        public void onNeighborConnected(Neighbor currentNeighbor) {
            // Do nothing...
        }

        @Override
        public void onNeighborDisconnected(Neighbor recentNeighbor) {
            // Do nothing...
        }

        @Override
        public void onNeighborsChanged(Set<Neighbor> currentNeighbors) {
            // Override this!
        }
    }

    private final Context mContext;
    private final NeighborChangeCallback mCallback;

    private final SortedSet<Neighbor> mCurrentNeighbors = new TreeSet<>();

    // CONSTRUCTORS
    public NeighborObserver(Context context) {
        super(new Handler());
        mContext = context.getApplicationContext();
        mCallback = null;
    }

    public NeighborObserver(Context context, Handler handler) {
        super(handler);
        mContext = context.getApplicationContext();
        mCallback = null;
    }

    public NeighborObserver(Context context, NeighborChangeCallback callback) {
        super(new Handler());
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    public NeighborObserver(Context context, Handler handler, NeighborChangeCallback callback) {
        super(handler);
        mContext = context.getApplicationContext();
        mCallback = callback;
    }

    /**
     * Register the observer to start watching the current neighbors.
     */
    public void register() {
        mContext.getContentResolver().registerContentObserver(
                NeighborProtocols.URI_CURRENT, false, this);
        updateCurrentNeighbors();
    }

    /**
     * Stop watching the current neighbors.
     */
    public void unregister() {
        mContext.getContentResolver().unregisterContentObserver(this);
        mCurrentNeighbors.clear();
    }

    /**
     * Updates the neighbors. If NeighobrObserver is registered for changes, this method is called
     * by the onChange method. Else, this method needs to be called before getNeighbors to get the
     * current neighbors.
     */
    public synchronized void updateCurrentNeighbors() {
        // TODO: use DbController directly when in CORE process
        final Cursor neighborCursor = mContext.getContentResolver().query(
                NeighborProtocols.URI_CURRENT, null, null, null, null);

        final HashSet<Neighbor> previousNeighbors = new HashSet<>(mCurrentNeighbors);
        synchronized (mCurrentNeighbors) {
            mCurrentNeighbors.clear();
            while (neighborCursor.moveToNext()) {
                mCurrentNeighbors.add(Neighbor.fromCursor(neighborCursor));
            }
        }
        neighborCursor.close();

        if (mCallback != null) {
            for (Neighbor oldNeighbor : Sets.difference(previousNeighbors, mCurrentNeighbors)) {
                mCallback.onNeighborDisconnected(oldNeighbor);
            }
            for (Neighbor newNeighbor : Sets.difference(mCurrentNeighbors, previousNeighbors)) {
                mCallback.onNeighborConnected(newNeighbor);
            }
            mCallback.onNeighborsChanged(new HashSet<>(mCurrentNeighbors));
        }
    }

    /**
     * @return a copy of the current neighbor list.
     */
    synchronized public SortedSet<Neighbor> getCurrentNeighbors() {
        return new TreeSet<Neighbor>(mCurrentNeighbors);
    }

    /**
     * @return the current number of neighbors
     */
    synchronized public int getNeighborCount() {
        return mCurrentNeighbors.size();
    }

    @Override
    public void onChange(boolean selfChange) {
        onChange(selfChange, null);
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        updateCurrentNeighbors();
    }

}
