
package ch.ethz.csg.oppnet.utils;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;

public abstract class TemporaryBroadcastReceiver extends BroadcastReceiver {
    private boolean mIsRegistered = false;

    protected abstract IntentFilter getIntentFilter();

    public void register(Context context) {
        if (!mIsRegistered) {
            context.registerReceiver(this, getIntentFilter());
            mIsRegistered = true;
        }
    }

    public void unregister(Context context) {
        if (mIsRegistered) {
            context.unregisterReceiver(this);
            mIsRegistered = false;
        }
    }
}
