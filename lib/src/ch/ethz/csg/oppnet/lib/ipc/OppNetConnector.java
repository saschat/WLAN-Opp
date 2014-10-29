
package ch.ethz.csg.oppnet.lib.ipc;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.widget.Toast;

import ch.ethz.csg.oppnet.lib.data.ExchangePacketObserver;
import ch.ethz.csg.oppnet.lib.data.ExchangePacketObserver.NewPacketCallback;
import ch.ethz.csg.oppnet.lib.data.OppNetContract;
import ch.ethz.csg.oppnet.lib.data.ProtocolDefinitionParser;
import ch.ethz.csg.oppnet.lib.data.ProtocolRegistry;
import ch.ethz.csg.oppnet.lib.data.TokenStore;

import java.util.HashMap;
import java.util.Map;

public final class OppNetConnector implements Handler.Callback {
    private static final String START_OPPNET = "ch.ethz.csg.oppnet.action.START_OPPNET";

    private static OppNetConnector sInstance;

    private final Context mContext;
    private final OppNetMessenger mMessenger;
    private final ProtocolRegistry mProtocolRegistry;

    private volatile boolean mPlatformAvailable;
    /**
     * Maps protocol names to packet observer callbacks.
     */
    private final Map<String, NewPacketCallback> mPacketCallbacks = new HashMap<>();

    /**
     * Maps protocol names to packet observers.
     */
    private final Map<String, ExchangePacketObserver> mPacketObservers = new HashMap<>();

    public static synchronized OppNetConnector getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new OppNetConnector(context);
        }
        return sInstance;
    }

    private OppNetConnector(Context context) {
        mContext = context.getApplicationContext();
        mMessenger = new OppNetMessenger(this);
        mProtocolRegistry = ProtocolRegistry.getInstance(mContext);
    }

    public boolean bind() {
        // Establish a connection with the service.
        mPlatformAvailable = mContext.bindService(
                new Intent(START_OPPNET), mMessenger, Context.BIND_AUTO_CREATE);
        if (mPlatformAvailable) {
            mMessenger.setApiKey(TokenStore.getApiKey(mContext));
        } else {
            Toast.makeText(mContext,
                    "The OppNet platform is currently not installed.",
                    Toast.LENGTH_SHORT).show();
        }
        return mPlatformAvailable;
    }

    public void unbind() {
        if (mPlatformAvailable) {
            mMessenger.sendCommand(OppNetMessenger.MSG_UNREGISTER_CLIENT);
            for (ExchangePacketObserver observer : mPacketObservers.values()) {
                observer.unregister();
            }
        }
        mContext.unbindService(mMessenger);
    }

    public void registerProtocolsFromResources(int resourceId, NewPacketCallback callback) {
        if (!mPlatformAvailable) {
            return;
        }

        Resources res = mContext.getResources();
        ProtocolDefinitionParser parser = new ProtocolDefinitionParser(res.getXml(resourceId));

        Bundle protocolDescription;
        while ((protocolDescription = parser.nextProtocol()) != null) {
            final String protocolName =
                    protocolDescription.getString(OppNetContract.Protocols.COLUMN_IDENTIFIER);

            final String protocolToken = mProtocolRegistry.getToken(protocolName);
            if (protocolToken != null) {
                // Already registered, try to add a packet observer
                try {
                    // XXX: This happens when a client was still running while the OppNet platform
                    // got uninstalled and then reinstalled. This leads to a replacement of protocol
                    // tokens, which allow or deny access to the content provider data. If we indeed
                    // have stale tokens, we let them replace as if there was no registered token
                    // before - if the token still works, nothing else needs to be done.
                    registerProtocolObserver(protocolName, protocolToken, callback);
                    return;
                } catch (SecurityException e) {
                    // Something is wrong with the previously registered protocol, clean up and try
                    // again.
                    final ExchangePacketObserver observer = mPacketObservers.remove(protocolName);
                    if (observer != null) {
                        observer.unregister();
                    }
                }
            }

            mMessenger.sendCommand(OppNetMessenger.MSG_REGISTER_PROTOCOL, protocolDescription);
            mPacketCallbacks.put(protocolName, callback);
        }
    }

    public void requestDiscovery() {
        if (mPlatformAvailable) {
            mMessenger.sendCommand(OppNetMessenger.MSG_ACTIVATE_DISCOVERY);
        }
    }

    // Handler.Callback
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case OppNetMessenger.MSG_REQUEST_API_KEY: {
                ApiKeyReceiver.requestApiKey(mContext);
                break;
            }

            case OppNetMessenger.MSG_REGISTER_PROTOCOL: {
                final Bundle protocolNameAndToken = msg.peekData();
                if (protocolNameAndToken != null) {
                    final String protocolName =
                            protocolNameAndToken.getString(OppNetMessenger.EXTRA_PROTOCOL_NAME);
                    final String protocolToken =
                            protocolNameAndToken.getString(OppNetMessenger.EXTRA_PROTOCOL_TOKEN);

                    mProtocolRegistry.add(protocolName, protocolToken);

                    NewPacketCallback callback = mPacketCallbacks.remove(protocolName);
                    if (callback == null) {
                        throw new IllegalStateException(
                                "No callback found to observe protocol " + protocolName);
                    } else {
                        registerProtocolObserver(protocolName, protocolToken, callback);
                    }
                }
                break;
            }

            default: {
                // Not one of our messages, let someone else handle it.
                return false;
            }
        }

        // We handled the message
        return true;
    }

    public void registerProtocolObserver(
            String protocolName, String protocolToken, NewPacketCallback callback) {
        ExchangePacketObserver packetObserver = new ExchangePacketObserver(
                mContext, new Handler(), protocolToken, callback);
        mPacketObservers.put(protocolName, packetObserver);

        packetObserver.register();
        packetObserver.onChange(true);
    }

    public void enqueuePacket(byte[] payload) {
        if (mPlatformAvailable) {
            final String protocolToken = mProtocolRegistry.getSingleToken();
            enqueuePacket(protocolToken, payload);
        }
    }

    public void enqueuePacket(String protocolToken, byte[] payload) {
        if (mPlatformAvailable) {
            ContentValues values = new ContentValues();
            values.put(OppNetContract.Packets.COLUMN_PAYLOAD, payload);

            Uri packetUri = OppNetContract.buildProtocolUri(
                    OppNetContract.Packets.URI_OUTGOING, protocolToken);

            ContentResolver resolver = mContext.getContentResolver();
            resolver.insert(packetUri, values);
        }
    }
}
