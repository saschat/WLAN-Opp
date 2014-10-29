
package ch.ethz.csg.oppnet.lib.data;

import android.os.Bundle;
import android.util.Log;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public class ProtocolDefinitionParser {
    private static final String TAG = ProtocolDefinitionParser.class.getSimpleName();

    private final XmlPullParser mParser;
    private boolean inProtocolsSection = false;
    private boolean allProtocolsParsed = false;

    public ProtocolDefinitionParser(XmlPullParser parser) {
        mParser = parser;
    }

    private boolean findProtocols() {
        try {
            int eventType = mParser.getEventType();
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && mParser.getName().equals("protocols")) {
                    if (!mParser.isEmptyElementTag()) {
                        inProtocolsSection = true;
                        return true;
                    }
                    break;
                }
                eventType = mParser.next();
            }
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error while looking for <protocols> start tag", e);
        }

        allProtocolsParsed = true;
        return false;
    }

    public Bundle nextProtocol() {
        if (allProtocolsParsed || (!inProtocolsSection && !findProtocols())) {
            return null;
        }

        try {
            if (mParser.nextTag() == XmlPullParser.END_TAG) {
                allProtocolsParsed = true;
                return null;
            }

            Log.v(TAG, "Parsing protocol:");
            Bundle protocolDefinition = new Bundle();
            while (mParser.nextTag() == XmlPullParser.START_TAG) {
                String tagName = mParser.getName();
                String content = mParser.nextText();

                switch (tagName) {
                    case "name": {
                        protocolDefinition.putString(
                                OppNetContract.Protocols.COLUMN_IDENTIFIER, content);
                        Log.v(TAG, "\tname: " + content);
                        break;
                    }

                    case "encrypted": {
                        boolean isEncrypted = Boolean.parseBoolean(content);
                        protocolDefinition.putBoolean(
                                OppNetContract.Protocols.COLUMN_ENCRYPTED, isEncrypted);
                        Log.v(TAG, "\tencrypted: " + isEncrypted);
                        break;
                    }

                    case "authenticated": {
                        boolean isAuthenticated = Boolean.parseBoolean(content);
                        protocolDefinition.putBoolean(
                                OppNetContract.Protocols.COLUMN_SIGNED, isAuthenticated);
                        Log.v(TAG, "\tauthenticated: " + isAuthenticated);
                        break;
                    }

                    case "defaultTTL": {
                        int ttl = Integer.parseInt(content);
                        protocolDefinition.putInt(
                                OppNetContract.Protocols.COLUMN_DEFAULT_TTL, ttl);
                        Log.v(TAG, "\tdefaultTTL: " + ttl);
                        break;
                    }

                    default: {
                        Log.d(TAG, String.format(
                                "Unknown tag in protocol definition: <%1$s>%2$s</%1$s>",
                                tagName, content));
                    }
                }

                if (mParser.getEventType() != XmlPullParser.END_TAG) {
                    mParser.next();
                }
            }
            return protocolDefinition;
        } catch (XmlPullParserException | IOException e) {
            Log.e(TAG, "Error while parsing <protocol> tag", e);
        }

        return null;
    }
}
