
package ch.ethz.csg.oppnet.network;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;

import ch.ethz.csg.oppnet.utils.TemporaryBroadcastReceiver;

public class ScanResultsReceiver extends TemporaryBroadcastReceiver {
    public static interface ScanResultsListener {
        public void onWifiScanCompleted();

        public void onBluetoothScanCompleted();

        public void onBluetoothDeviceFound(BluetoothDevice btDevice);
    }

    private final ScanResultsListener mListener;

    public ScanResultsReceiver(ScanResultsListener listener) {
        mListener = listener;
    }

    @Override
    protected IntentFilter getIntentFilter() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        return filter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        switch (intent.getAction()) {
            case WifiManager.SCAN_RESULTS_AVAILABLE_ACTION: {
                mListener.onWifiScanCompleted();
                break;
            }

            case BluetoothAdapter.ACTION_DISCOVERY_FINISHED: {
                mListener.onBluetoothScanCompleted();
                break;
            }

            case BluetoothDevice.ACTION_FOUND: {
                final BluetoothDevice device =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                mListener.onBluetoothDeviceFound(device);
                break;
            }
        }
    }
}
