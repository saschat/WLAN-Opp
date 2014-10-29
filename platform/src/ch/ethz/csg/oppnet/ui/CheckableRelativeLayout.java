
package ch.ethz.csg.oppnet.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Checkable;
import android.widget.RelativeLayout;

import java.util.ArrayList;
import java.util.List;

public class CheckableRelativeLayout extends RelativeLayout implements Checkable {
    private boolean mIsChecked;
    private final List<Checkable> mCheckableViews = new ArrayList<>();

    public CheckableRelativeLayout(Context context) {
        super(context);
    }

    public CheckableRelativeLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CheckableRelativeLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        findCheckableChildren(this);
    }

    private void findCheckableChildren(View v) {
        if (v != this && (v instanceof Checkable)) {
            // Deactivate the subview and let us handle click events
            v.setClickable(false);
            mCheckableViews.add((Checkable) v);
        }

        if (v instanceof ViewGroup) {
            final ViewGroup vg = (ViewGroup) v;
            final int childCount = vg.getChildCount();
            for (int i = 0; i < childCount; ++i) {
                findCheckableChildren(vg.getChildAt(i));
            }
        }
    }

    @Override
    public boolean isChecked() {
        return mIsChecked;
    }

    @Override
    public void setChecked(boolean checked) {
        mIsChecked = checked;

        for (Checkable checkable : mCheckableViews) {
            checkable.setChecked(checked);
        }
    }

    @Override
    public void toggle() {
        mIsChecked = !mIsChecked;

        for (Checkable checkable : mCheckableViews) {
            checkable.toggle();
        }
    }
}
