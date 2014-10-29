
package ch.ethz.csg.oppnet.ui;

import android.content.Context;
import android.database.Cursor;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;

public abstract class CursorBackedArrayAdapter<T> extends BaseAdapter {
    private final LayoutInflater mInflater;
    private final ArrayList<T> mObjects = new ArrayList<>();
    private Cursor mCursor;

    private static class ViewHolder {
        TextView title;
        TextView details;
    }

    public CursorBackedArrayAdapter(Context context) {
        mInflater = LayoutInflater.from(context);
    }

    protected abstract void objectsFromCursor(Cursor cursor, ArrayList<T> objects);

    public void swapCursor(Cursor newCursor) {
        if (newCursor == mCursor) {
            // The cursor has already been read before
            return;
        }

        mObjects.clear();
        if (newCursor != null) {
            objectsFromCursor(newCursor, mObjects);
        }
        notifyDataSetChanged();
        mCursor = newCursor;
    }

    protected abstract String getTitleText(T item);

    protected abstract String getDetailsText(T item);

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        // A ViewHolder keeps references to children views to avoid unneccessary calls
        // to findViewById() on each row.
        ViewHolder holder;

        // When convertView is not null, we can reuse it directly, there is no need
        // to reinflate it. We only inflate a new View when the convertView supplied
        // by ListView is null.
        if (convertView == null) {
            convertView = mInflater.inflate(android.R.layout.simple_list_item_2, null);

            // Creates a ViewHolder and store references to the two children views.
            holder = new ViewHolder();
            holder.title = (TextView) convertView.findViewById(android.R.id.text1);
            holder.details = (TextView) convertView.findViewById(android.R.id.text2);

            convertView.setTag(holder);
        } else {
            // Get the ViewHolder back to get fast access to the TextViews.
            holder = (ViewHolder) convertView.getTag();
        }

        // Bind the data efficiently with the holder.
        T item = getItem(position);
        holder.title.setText(getTitleText(item));
        holder.details.setText(getDetailsText(item));

        return convertView;
    }

    @Override
    public int getCount() {
        return mObjects.size();
    }

    @Override
    public T getItem(int position) {
        return mObjects.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }
}
