
package ch.ethz.csg.oppnet.core;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import ch.ethz.csg.oppnet.core.SupervisorService.SupervisorBinder;
import ch.ethz.csg.oppnet.data.Implementation;
import ch.ethz.csg.oppnet.lib.ipc.OppNetMessenger;

import java.util.HashMap;

/**
 * Endpoint of the OppNetConnector component used by OppNet client applications. This service allows
 * a client to register itself and the protocols it supports, as well as to trigger some operation
 * modes of the OppNet platform.
 * <p>
 * This service is only meant to be bound to by client apps. Other platform components can directly
 * use the {@link SupervisorService}.
 */
public class ConnectorService extends Service
        implements Handler.Callback, SupervisorService.Callback {
    private static final String TAG = ConnectorService.class.getSimpleName();

    /**
     * Maps app tokens of bound clients to reply messengers.
     */
    private final HashMap<String, Messenger> mConnectedClients = new HashMap<>();

    /**
     * Handles messages from bound client apps.
     */
    private Messenger mMessenger = new Messenger(new Handler(ConnectorService.this));

    private SupervisorService mSupervisor;
    private ServiceConnection mSupervisorConnection;

    @Override
    public void onCreate() {
        super.onCreate();

        mSupervisorConnection = new ServiceConnection() {
            @Override
            public void onServiceDisconnected(ComponentName name) {
                mSupervisor = null;
            }

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                mSupervisor = ((SupervisorBinder) service).getSupervisor();
                mSupervisor.addCallback(ConnectorService.this);
            }
        };
        SupervisorService.bindSupervisorService(this, mSupervisorConnection);
    }

    @Override
    public void onDestroy() {
        unbindService(mSupervisorConnection);
        mSupervisorConnection = null;

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    @Override
    public void onActivationStateChanged(boolean activated) {
        for (String clientApiKey : mConnectedClients.keySet()) {
            reply(clientApiKey, OppNetMessenger.MSG_ACTIVATE_DISCOVERY, activated);
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        final Bundle extraData = msg.getData();

        final String apiKey = extraData.getString(OppNetMessenger.EXTRA_API_KEY);
        if (apiKey == null) {
            // This service does not work without API keys
            tryReplyToLetClientRequestApiKey(msg.replyTo);
            return true;
        } else {
            // Clean extra data
            extraData.remove(OppNetMessenger.EXTRA_API_KEY);
        }

        final OppNetApplication app = (OppNetApplication) getApplication();
        final String appName = app.applicationRegistry.getPackageFromToken(apiKey);
        if (appName == null) {
            Log.w(TAG, "API key " + apiKey + " does not belong to a registered client app!");
            tryReplyToLetClientRequestApiKey(msg.replyTo);
            return true;
        }

        switch (msg.what) {
            case OppNetMessenger.MSG_REGISTER_CLIENT: {
                final Messenger replyTo = msg.replyTo;
                if (replyTo != null) {
                    registerClient(appName, apiKey, replyTo);
                }
                break;
            }

            case OppNetMessenger.MSG_UNREGISTER_CLIENT: {
                unregisterClient(appName, apiKey);
                break;
            }

            case OppNetMessenger.MSG_REGISTER_PROTOCOL: {
                if (!extraData.isEmpty()) {
                    registerProtocol(apiKey, extraData);
                }
                break;
            }

            case OppNetMessenger.MSG_ACTIVATE_DISCOVERY: {
                final boolean granted = mSupervisor.requestBeaconing();
                reply(apiKey, OppNetMessenger.MSG_ACTIVATE_DISCOVERY, granted);
                break;
            }

            default: {
                return false;
            }
        }

        return true;
    }

    private void registerClient(String appName, String apiKey, Messenger replyTo) {
        final Messenger prev = mConnectedClients.put(apiKey, replyTo);
        if (prev == null) {
            Log.v(TAG, "Client app " + appName + " has connected.");
        }
    }

    private void unregisterClient(String appName, String apiKey) {
        final Messenger prev = mConnectedClients.remove(apiKey);
        if (prev != null) {
            Log.v(TAG, "Client app " + appName + " has disconnected.");
        }
    }

    private void registerProtocol(String apiKey, Bundle protocolDescription) {
        final OppNetApplication app = (OppNetApplication) getApplication();
        final Implementation impl =
                app.protocolRegistry.registerProtocol(apiKey, protocolDescription);

        if (impl != null) {
            final Bundle replyData = new Bundle();
            replyData.putString(OppNetMessenger.EXTRA_PROTOCOL_NAME, impl.getProtocolName());
            replyData.putString(OppNetMessenger.EXTRA_PROTOCOL_TOKEN, impl.getToken());
            reply(apiKey, OppNetMessenger.MSG_REGISTER_PROTOCOL, replyData);
        }
    }

    private void reply(String apiKey, int msgCode, boolean flag) {
        reply(apiKey, msgCode, flag, null);
    }

    private void reply(String apiKey, int msgCode, Bundle data) {
        reply(apiKey, msgCode, null, data);
    }

    private void reply(String apiKey, int msgCode, Boolean flag, Bundle dataBundle) {
        Message reply = Message.obtain();
        reply.what = msgCode;
        reply.arg1 = (flag != null && flag) ? 1 : 0;
        reply.setData(dataBundle);

        try {
            mConnectedClients.get(apiKey).send(reply);
        } catch (RemoteException e) {
            // Client is not connected anymore
            mConnectedClients.remove(apiKey);
        }
    }

    private void tryReplyToLetClientRequestApiKey(Messenger replyTo) {
        if (replyTo != null) {
            Message reply = Message.obtain();
            reply.what = OppNetMessenger.MSG_REQUEST_API_KEY;

            try {
                replyTo.send(reply);
            } catch (RemoteException e) {
                // Client is not connected anymore, but hasn't been registered before, either.
            }
        }
    }
}
