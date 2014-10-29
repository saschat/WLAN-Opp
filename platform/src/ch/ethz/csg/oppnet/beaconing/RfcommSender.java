
package ch.ethz.csg.oppnet.beaconing;

import android.bluetooth.BluetoothDevice;
import android.database.Cursor;

import ch.ethz.csg.oppnet.data.FullContract.Neighbors;
import ch.ethz.csg.oppnet.lib.data.Neighbor;
import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import java.util.HashSet;
import java.util.Set;

public class RfcommSender extends InterruptibleFailsafeRunnable {
    public static final String TAG = "BluetoothSender";

    private BeaconingManager mBM;

    public RfcommSender(BeaconingManager beaconingManager) {
        super(TAG);
        mBM = beaconingManager;
    }

    @Override
    public void execute() {
        // Get recent neighbors with a bluetooth address from content provider
        final Set<BluetoothDevice> recentNeighbors = new HashSet<>();
        final Cursor recentNeighborCursor =
                mBM.mDbController.getNeighborsCursor(BeaconingManager.getRecentTimestamp());
        final int colIdxBtAddr =
                recentNeighborCursor.getColumnIndexOrThrow(Neighbors.COLUMN_BLUETOOTH);

        while (recentNeighborCursor.moveToNext()) {
            // We're only interested in neighbors of which we know a bluetooth address
            if (recentNeighborCursor.isNull(colIdxBtAddr)) {
                // NOTE: In this cursor, one neighbor may be represented by multiple rows (when
                // they implement multiple protocols). As the rows are ordered by neighbor by
                // default, this condition here makes sure to skip *all* rows associated to one
                // single neighbor lacking a bluetooth address (it won't be set on *any* of its
                // rows).
                continue;
            }

            // At this point, we're sure that the neighbor has a bluetooth address stored
            final Neighbor neighbor = Neighbor.fromCursor(recentNeighborCursor);
            final BluetoothDevice btDevice =
                    mBM.mNetManager.getBluetoothDevice(neighbor.getBluetoothAddress());

            if (btDevice != null && !mBM.mBluetoothSockets.containsKey(btDevice.getAddress())) {
                recentNeighbors.add(btDevice);
            }
        }
        recentNeighborCursor.close();

        // Try to connect to each of the recent neighbors
        for (BluetoothDevice btDevice : recentNeighbors) {
            mBM.connectToBtDevice(btDevice);
        }
    }
}
