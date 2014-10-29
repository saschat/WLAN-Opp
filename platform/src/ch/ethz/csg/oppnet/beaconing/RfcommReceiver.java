
package ch.ethz.csg.oppnet.beaconing;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import java.io.IOException;

public class RfcommReceiver extends InterruptibleFailsafeRunnable {
    public static final String TAG = "BluetoothReceiver";
    private final BeaconingManager mBeaconingManager;
    private BluetoothServerSocket mServerSocket;

    public RfcommReceiver(BeaconingManager beaconingManager) throws IOException {
        super(TAG);
        mBeaconingManager = beaconingManager;

        mServerSocket = mBeaconingManager.mNetManager.getBluetoothServerSocket(
                BeaconingManager.SDP_NAME, BeaconingManager.OPP_NET_UUID);
    }

    @Override
    public void execute() {
        while (!mThread.isInterrupted()) {
            final BluetoothSocket btSocket;
            try {
                btSocket = mServerSocket.accept(BeaconingManager.RECEIVER_SOCKET_TIMEOUT);
            } catch (IOException e) {
                // Timeout
                continue;
            }

            // Stop bluetooth scan, if any was in progress
            mBeaconingManager.mNetManager.doBluetoothScan(false);

            final BluetoothDevice btDevice = btSocket.getRemoteDevice();
            Log.d(TAG, String.format("Remote bluetooth device %s (%s) has connected.",
                    btDevice.getName(), btDevice.getAddress()));

            try {
                final InterruptibleFailsafeRunnable btConnection =
                        new RfcommConnection(mBeaconingManager, btSocket, false);

                mBeaconingManager.mBluetoothSockets.put(btDevice.getAddress(), btSocket);
                mBeaconingManager.mThreadPool.execute(btConnection);
            } catch (IOException e) {
                // Something went wrong with that socket.
                continue;
            }
        }
    }
}
