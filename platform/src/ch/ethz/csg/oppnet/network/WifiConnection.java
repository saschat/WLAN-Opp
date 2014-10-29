
package ch.ethz.csg.oppnet.network;

import android.net.DhcpInfo;
import android.net.wifi.WifiInfo;

import com.google.common.base.Optional;
import com.google.common.net.InetAddresses;
import com.google.common.primitives.Ints;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;

public class WifiConnection {
    private final Optional<Inet4Address> mIp4Address;
    private final Optional<Inet6Address> mIp6Address;
    private final Optional<InetAddress> mApAddress;
    private final Optional<String> mNetworkName;
    private final NetworkInterface mWifiInterface;

    public static WifiConnection fromStaMode(
            NetworkInterface iface, DhcpInfo dhcp, WifiInfo connection) {

        Inet4Address ip4 = null;
        Inet6Address ip6 = null;
        for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
            InetAddress addr = ifaceAddr.getAddress();
            if (addr instanceof Inet4Address && ip4 == null) {
                ip4 = (Inet4Address) addr;
            } else if (addr instanceof Inet6Address && ip6 == null) {
                ip6 = (Inet6Address) addr;
            }
        }

        InetAddress apIp = null;
        if (dhcp.gateway != 0) {
            try {
                apIp = InetAddresses.fromLittleEndianByteArray(Ints.toByteArray(dhcp.gateway));
            } catch (UnknownHostException e) {
                // No valid IP address
            }
        }

        String networkName = connection.getSSID();
        if (networkName != null) {
            networkName = NetworkManager.unquoteSSID(networkName);
        }

        return new WifiConnection(ip4, ip6, apIp, networkName, iface);
    }

    public static WifiConnection fromApMode(NetworkInterface iface, String apName) {
        Inet4Address ip4 = null;
        Inet6Address ip6 = null;
        for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
            InetAddress addr = ifaceAddr.getAddress();
            if (addr instanceof Inet4Address && ip4 == null) {
                ip4 = (Inet4Address) addr;
            } else if (addr instanceof Inet6Address && ip6 == null) {
                ip6 = (Inet6Address) addr;
            }
        }

        return new WifiConnection(ip4, ip6, null, apName, iface);
    }

    private WifiConnection(Inet4Address ip4, Inet6Address ip6, InetAddress apIp, String ssid,
            NetworkInterface wifiInterface) {
        mIp4Address = Optional.fromNullable(ip4);
        mIp6Address = Optional.fromNullable(ip6);
        mApAddress = Optional.fromNullable(apIp);
        mNetworkName = Optional.fromNullable(ssid);
        mWifiInterface = wifiInterface;
    }

    public boolean hasIp4Address() {
        return mIp4Address.isPresent();
    }

    public Optional<Inet4Address> getIp4Address() {
        return mIp4Address;
    }

    public boolean hasIp6Address() {
        return mIp6Address.isPresent();
    }

    public Optional<Inet6Address> getIp6Address() {
        return mIp6Address;
    }

    public boolean hasApAddress() {
        return mApAddress.isPresent();
    }

    public Optional<InetAddress> getApAddress() {
        return mApAddress;
    }

    public boolean hasNetworkName() {
        return mNetworkName.isPresent();
    }

    public Optional<String> getNetworkName() {
        return mNetworkName;
    }

    public boolean isConnected() {
        return mNetworkName.isPresent();
    }

    public NetworkInterface getWifiInterface() {
        return mWifiInterface;
    }
}
