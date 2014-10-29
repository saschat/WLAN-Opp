
package ch.ethz.csg.oppnet.ui;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;

import java.util.Random;

public abstract class CursorBackedArrayListFragment<T> extends ListFragment
        implements LoaderManager.LoaderCallbacks<Cursor> {
    public static final String KEY_LOADER_ID = "loader_id";

    private int mLoaderId;
    private CursorBackedArrayAdapter<T> mAdapter;

    protected abstract CursorBackedArrayAdapter<T> getCursorArrayAdapter(Context context);
    protected abstract String getEmptyString();
    protected abstract Uri getLoaderUri();

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLoaderId = getArguments().getInt(KEY_LOADER_ID, new Random().nextInt());
        mAdapter = getCursorArrayAdapter(getActivity());
        setListAdapter(mAdapter);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        getLoaderManager().initLoader(mLoaderId, null, this);
        setEmptyText(getEmptyString());
    }

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        // Let the content provider apply the default projection, filter & sort order
        return new CursorLoader(
                getActivity(), getLoaderUri(), null, null, null, null);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        mAdapter.swapCursor(data);
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {
        mAdapter.swapCursor(null);
    }
}
