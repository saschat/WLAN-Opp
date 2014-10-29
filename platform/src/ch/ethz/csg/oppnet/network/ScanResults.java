
package ch.ethz.csg.oppnet.network;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ScanResults {
    private final List<ScanResult> mAvailableNetworks = new ArrayList<>();

    public ScanResults(
            List<ScanResult> availableNetworks, List<WifiConfiguration> configuredNetworks) {

        final Set<String> knownSSIDs = new HashSet<>(configuredNetworks.size());
        for (final WifiConfiguration network : configuredNetworks) {
            knownSSIDs.add(NetworkManager.unquoteSSID(network.SSID));
        }

        for (final ScanResult network : availableNetworks) {
            if (NetworkManager.isOppNetSSID(network.SSID)
                    || (NetworkManager.isOpenNetwork(network) && network.level > -90)
                    || knownSSIDs.contains(network.SSID)) {
                mAvailableNetworks.add(network);
            }
        }

        Collections.sort(mAvailableNetworks, new SignalStrengthSorter());
    }

    public boolean hasConnectibleNetworks() {
        return (mAvailableNetworks.size() > 0);
    }

    public List<ScanResult> getConnectibleNetworks() {
        return mAvailableNetworks;
    }

    private class SignalStrengthSorter implements Comparator<ScanResult> {
        @Override
        public int compare(ScanResult lhs, ScanResult rhs) {
            final int lhsNetworkType;
            if (NetworkManager.isOppNetSSID(lhs.SSID)) {
                lhsNetworkType = 1;
            } else if (NetworkManager.isOpenNetwork(lhs)) {
                lhsNetworkType = 2;
            } else {
                lhsNetworkType = 3;
            }

            final int rhsNetworkType;
            if (NetworkManager.isOppNetSSID(rhs.SSID)) {
                rhsNetworkType = 1;
            } else if (NetworkManager.isOpenNetwork(rhs)) {
                rhsNetworkType = 2;
            } else {
                rhsNetworkType = 3;
            }

            if (lhsNetworkType == rhsNetworkType) {
                return (0 - lhs.level + rhs.level);
            } else {
                return (lhsNetworkType - rhsNetworkType);
            }
        }
    }
}
