
package ch.ethz.csg.oppnet.beaconing;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import ch.ethz.csg.oppnet.beaconing.BeaconParser.PossibleBeacon;
import ch.ethz.csg.oppnet.utils.InterruptibleFailsafeRunnable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class RfcommConnection extends InterruptibleFailsafeRunnable {
    public static final String TAG = "BluetoothConnection";

    private final BeaconingManager mBM;
    private final boolean mIsInitiator;
    private final BluetoothSocket mSocket;
    private final InputStream mInStream;
    private final OutputStream mOutStream;

    public RfcommConnection(
            BeaconingManager beaconingManager, BluetoothSocket btSocket, boolean isConnected)
            throws IOException {

        super(TAG);
        mBM = beaconingManager;

        mIsInitiator = isConnected;
        mSocket = btSocket;
        mInStream = mSocket.getInputStream();
        mOutStream = mSocket.getOutputStream();
    }

    @Override
    public void execute() {
        final BluetoothDevice btDevice = mSocket.getRemoteDevice();
        final String deviceName = btDevice.getName();
        final String deviceAddr = btDevice.getAddress();

        if (mIsInitiator && !connect()) {
            Log.d(TAG, String.format(
                    "Remote device %s (%s) is unreachable.", deviceName, deviceAddr));
            disconnect();
            return;
        }
        Log.d(TAG, String.format(
                "Remote device %s (%s) is now connected", deviceName, deviceAddr));

        int length;
        byte[] buffer = new byte[BeaconingManager.RECEIVER_BUFFER_SIZE];
        // Keep connection open and listen
        try {
            length = mInStream.read(buffer);
        } catch (IOException e) {
            Log.d(TAG, String.format(
                    "Remote device %s (%s) has disconnected.", deviceName, deviceAddr));
            disconnect();
            return;
        }
        final long timeReceived = System.currentTimeMillis() / 1000;

        // Reply with beacon, if the remote device initiated the connection
        if (!mIsInitiator) {
            final byte[] reply = mBM.mBeaconBuilder.buildBeacon(
                    mBM.mNetManager.getWifiState(),
                    mBM.mNetManager.getCurrentConnection(),
                    mBM.mProtocolRegistry.getAllProtocolImplementations().keySet(),
                    mBM.mDbController.getNeighbors(BeaconingManager.getCurrentTimestamp()));

            try {
                mOutStream.write(reply);
            } catch (IOException e) {
                disconnect();
            }
        }

        // Put received beacon into parser queue
        PossibleBeacon possibleBeacon = PossibleBeacon.from(
                buffer, length, timeReceived,
                mSocket.getRemoteDevice().getAddress(),
                mBM.mMasterIdentity);
        mBM.mBeaconParser.addProcessableBeacon(possibleBeacon);

        // Close socket
        disconnect();
    }

    private boolean connect() {
        try {
            mSocket.connect();

            // Send initial beacon
            final byte[] beacon = mBM.mBeaconBuilder.buildBeacon(
                    mBM.mNetManager.getWifiState(),
                    mBM.mNetManager.getCurrentConnection(),
                    mBM.mProtocolRegistry.getAllProtocolImplementations().keySet(),
                    mBM.mDbController.getNeighbors(BeaconingManager.getCurrentTimestamp()));

            mOutStream.write(beacon);
        } catch (IOException e) {
            // Connection attempt failed
            return false;
        }
        return true;
    }

    private void disconnect() {
        // Close socket
        try {
            mBM.onBtDeviceDisconnected(mSocket.getRemoteDevice());
            mSocket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error while closing bluetooth socket:", e);
        }
    }
}
