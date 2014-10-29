
package ch.ethz.csg.oppnet.lib.ipc;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayDeque;

public final class OppNetMessenger implements ServiceConnection {
    private static final String TAG = OppNetMessenger.class.getSimpleName();

    protected enum MessagePriority {
        LOW, MEDIUM, HIGH
    }

    // Message codes
    public static final int MSG_REQUEST_API_KEY = 1;
    public static final int MSG_REGISTER_CLIENT = 2;
    public static final int MSG_UNREGISTER_CLIENT = 3;
    public static final int MSG_REGISTER_PROTOCOL = 4;
    public static final int MSG_ACTIVATE_DISCOVERY = 23;

    public static final String EXTRA_API_KEY = "api_key";
    public static final String EXTRA_PROTOCOL_NAME = "protocol_name";
    public static final String EXTRA_PROTOCOL_TOKEN = "protocol_token";

    private final Messenger mOwnMessenger;
    private String mApiKey;
    private Messenger mServiceMessenger;

    // Message sending state
    private static final ArrayDeque<Message> sMessageQueue = new ArrayDeque<Message>();
    private MessageSenderRunnable mMessageSenderRunnable;

    protected OppNetMessenger(Handler.Callback callback) {
        mOwnMessenger = new Messenger(new Handler(callback));
    }

    protected void setApiKey(String newKey) {
        mApiKey = newKey;
    }

    protected void sendCommand(int msgCode) {
        sendCommand(msgCode, new Bundle(), null, MessagePriority.MEDIUM);
    }

    protected void sendCommand(int msgCode, Bundle data) {
        sendCommand(msgCode, data, null, MessagePriority.MEDIUM);
    }

    private void sendCommand(int msgCode, Bundle data, Messenger replyTo, MessagePriority priority) {
        data.putString(EXTRA_API_KEY, mApiKey);

        Message msg = Message.obtain();
        msg.what = msgCode;
        msg.setData(data);
        msg.replyTo = replyTo;

        synchronized (sMessageQueue) {
            switch (priority) {
                case HIGH: {
                    sMessageQueue.addFirst(msg);
                    break;
                }

                case MEDIUM:
                case LOW: {
                    sMessageQueue.add(msg);
                    break;
                }
            }
            sMessageQueue.notifyAll();
        }
    }

    private void registerClient() {
        sendCommand(MSG_REGISTER_CLIENT, new Bundle(), mOwnMessenger, MessagePriority.HIGH);
    }

    // ServiceConnection interface methods
    public void onServiceConnected(ComponentName className, IBinder service) {
        // This is called when the connection with the service has been
        // established, giving us the service object we can use to
        // interact with the service. We are communicating with our
        // service through an IDL interface, so get a client-side
        // representation of that from the raw service object.
        mServiceMessenger = new Messenger(service);
        registerClient();

        // Start sender thread
        mMessageSenderRunnable = new MessageSenderRunnable();
        new Thread(mMessageSenderRunnable).start();

        // We want to monitor the service for as long as we are
        // connected to it.
    }

    public void onServiceDisconnected(ComponentName className) {
        // This is called when the connection with the service has been
        // unexpectedly disconnected -- that is, its process crashed.
        mMessageSenderRunnable.terminate();
        mMessageSenderRunnable = null;
    }

    private class MessageSenderRunnable implements Runnable {
        private volatile boolean running = true;

        public void terminate() {
            running = false;
        }

        @Override
        public void run() {
            while (running || !sMessageQueue.isEmpty()) {
                synchronized (sMessageQueue) {
                    while (!sMessageQueue.isEmpty()) {
                        Message msg = sMessageQueue.poll();
                        if (msg != null) {
                            try {
                                mServiceMessenger.send(msg);
                            } catch (RemoteException e) {
                                // In this case the service has crashed before we could even
                                // do anything with it; we can count on soon being
                                // disconnected (and then reconnected if it can be restarted)
                                // so there is no need to do anything here.
                                Log.w(TAG, "OppNet service is down, terminating Messenger");
                                sMessageQueue.addFirst(msg);
                                terminate();
                                break;
                            }
                        }
                    }

                    try {
                        sMessageQueue.wait();
                    } catch (InterruptedException e) {
                        continue;
                    }
                }
            }
        }
    }
}
