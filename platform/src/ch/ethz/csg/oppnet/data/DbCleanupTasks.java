
package ch.ethz.csg.oppnet.data;

import android.content.Context;
import android.util.Log;

public final class DbCleanupTasks {

    public static class ExpiredPacketsCleanupTask implements Runnable {
        private static final String TAG = ExpiredPacketsCleanupTask.class.getSimpleName();
        private final Context mContext;

        public ExpiredPacketsCleanupTask(Context context) {
            super();
            mContext = context;
        }

        @Override
        public void run() {
            final DbController dbController = new DbController(mContext);

            final long currentTime = System.currentTimeMillis() / 1000;
            final int deleteCount = dbController.deleteExpiredPackets(currentTime);

            Log.v(TAG, String.format(
                    "Deleted %d packets with a TTL lower than %d", deleteCount, currentTime));
        }
    }
}
