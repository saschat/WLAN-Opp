
package ch.ethz.csg.oppnet.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;

import ch.ethz.csg.oppnet.data.ConfigurationStore;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Defines the set of {@link Feature}s and the {@link BeaconingInterval} the OppNet platform is
 * allowed to use to find neighbors and exchange data with them. Disabling certain features helps
 * saving power, as the platform should constantly run in the background to be effective.
 * 
 * @see BeaconingInterval
 * @see Feature
 * @author brunf
 */
public enum Policy {
    /**
     * This is the baseline policy, which uses just what's available anyways to find neighbors. It
     * does not by itself alter the networking state or anything else, it does not even send beacons
     * on a regular basis (only on network changes or as answer to received beacons). This policy is
     * designed to always run in the background without significant battery drain.
     */
    PASSIVE(BeaconingInterval.OFF),
    /**
     * This policy trades a small amount of power for a (usually significant) gain in neighbor
     * connectivity by allowing to actively use the WiFi infrastructure mode and switch between
     * discovered networks.
     */
    LOW_POWER(BeaconingInterval.SLOW,
            Feature.WIFI_CLIENT),
    /**
     * This policy tries to increase connectivity even further by using Bluetooth. As setting the
     * device to be discoverable over Bluetooth will require user interaction, this policy will only
     * use Bluetooth to listen to other devices making themselves discoverable (i.e. devices which
     * are in disaster mode), or to try and connect to known neighbors if there is no open WLAN
     * around.
     */
    LOW_POWER_PLUS(BeaconingInterval.SLOW,
            Feature.WIFI_CLIENT,
            Feature.BLUETOOTH),
    /**
     * This policy uses both WiFi modes to reach as many neighbors as possible. Since the AP mode
     * has a significant impact on battery life, this policy tries to save power by aggressively
     * switching networks in WiFi infrastructure mode and more frequent beaconing.
     */
    HIGH_CONNECTIVITY(BeaconingInterval.MEDIUM,
            Feature.WIFI_CLIENT,
            Feature.WIFI_AP),
    /**
     * This policy uses all available features to maximize the likelihood of finding neighbors as
     * fast as possible. It also drains the battery the fastest, and therefore it should only be
     * enabled with the user's consent.
     */
    DISASTER(BeaconingInterval.FAST,
            Feature.values());

    private final BeaconingInterval mBeaconInterval;
    private final Set<Feature> mSupportedFeatures;

    private Policy(BeaconingInterval beaconInterval, Feature... features) {
        mBeaconInterval = beaconInterval;

        final List<Feature> featureList = Arrays.asList(features);
        mSupportedFeatures =
                featureList.size() > 0 ?
                        EnumSet.copyOf(featureList) : EnumSet.noneOf(Feature.class);
    }

    public BeaconingInterval getBeaconingInterval() {
        return mBeaconInterval;
    }

    public boolean allows(Feature feature) {
        return mSupportedFeatures.contains(feature);
    }

    /**
     * Broadcast action when the current policy has changed. The new policy in effect is available
     * as the {@link PolicyManager#EXTRA_NEW_POLICY NEW_POLICY} string extra on the broadcast
     * intent.
     */
    public static final String ACTION_POLICY_CHANGED = "ch.ethz.csg.oppnet.action.POLICY_CHANGED";

    /**
     * The lookup key for the string extra in the POLICY_CHANGED broadcast intent containing the
     * name of the new policy (after the change).
     * 
     * @see Intent#getStringExtra(String)
     */
    public static final String EXTRA_NEW_POLICY = "ch.ethz.csg.oppnet.extra.NEW_POLICY";

    /**
     * The default policy, if none has been set before.
     */
    public static final Policy DEFAULT_POLICY = Policy.PASSIVE;

    /**
     * Returns the currently active policy.
     * 
     * @param context
     * @return the currently active policy
     */
    public static Policy getCurrentPolicy(Context context) {
        return ConfigurationStore.getCurrentPolicy(context);
    }

    /**
     * Replaces the currently active policy with a new one. If the new one is a different one, a
     * local broadcast is sent out to inform other components of the platform about the change.
     * 
     * @param context
     * @param newPolicy the new policy to set as currently active
     * @return true if the policy has changed (new policy is not the current one), false otherwise
     */
    public static synchronized boolean setCurrentPolicy(Context context, Policy newPolicy) {
        final Policy currentPolicy = getCurrentPolicy(context);

        if (!newPolicy.equals(currentPolicy)) {
            ConfigurationStore.saveCurrentPolicy(context, newPolicy);

            // Send local broadcast that the current policy changed
            LocalBroadcastManager.getInstance(context).sendBroadcast(
                    new Intent(ACTION_POLICY_CHANGED)
                            .putExtra(EXTRA_NEW_POLICY, newPolicy));

            return true;
        }
        return false;
    }

    /**
     * Convenience function to register a receiver for the
     * {@link PolicyManager#ACTION_POLICY_CHANGED POLICY_CHANGED} local broadcast.
     * 
     * @param context
     * @param receiver
     */
    public static void registerPolicyChangedReceiver(Context context, BroadcastReceiver receiver) {
        LocalBroadcastManager.getInstance(context).registerReceiver(
                receiver, new IntentFilter(ACTION_POLICY_CHANGED));
    }

    /**
     * Convenience function to unregister a previously registered local broadcast receiver.
     * 
     * @param context
     * @param receiver
     * @see PolicyManager#registerPolicyChangedReceiver(Context, BroadcastReceiver)
     */
    public static void unregisterPolicyChangedReceiver(Context context, BroadcastReceiver receiver) {
        LocalBroadcastManager.getInstance(context).unregisterReceiver(receiver);
    }

    /**
     * Represents the interval between two beaconing periods. In between, the platform is supposed
     * to sleep to save power. Setting the BeaconingInterval to {@link BeaconingInterval#OFF OFF}
     * means that no regular beaconing will take place.
     * 
     * @author brunf
     */
    public static enum BeaconingInterval {
        /**
         * No periodic beaconing. Beacons are only sent upon network changes or answering to other
         * beacons.
         */
        OFF(-1),
        /**
         * Periodic beaconing every 10 minutes. Lets the platform sleep the longest, therefore
         * saving the most power.
         */
        SLOW(10),
        /**
         * Periodic beaconing every 5 minutes. Trades power consumption for connectivity.
         */
        MEDIUM(5),
        /**
         * Periodic beaconing every 2 minutes. Platform will rarely be able to sleep.
         */
        FAST(2);

        private final int mInterval;

        private BeaconingInterval(int minutes) {
            mInterval = Math.max(-1, minutes * 60 * 1000);
        }

        public int getIntervalMillis() {
            return mInterval;
        }

        /**
         * Calculates the start timestamp for the next beaconing period.
         * 
         * @return a long indicating the timestamp in milliseconds when to schedule the next round
         *         of beaconing
         */
        public long getNextBeaconTimeMillis() {
            final long currentTime = System.currentTimeMillis();
            if (mInterval > 0) {
                return ((currentTime / mInterval) + 1) * mInterval;
            }
            throw new IllegalStateException("Beaconing is turned off.");
        }
    }

    /**
     * Represents a feature of the OppNet platform which can be dynamically turned on or off to save
     * power or increase overall connectivity.
     * 
     * @author brunf
     */
    public static enum Feature {
        /**
         * Allows the platform to enable WLAN in client mode and switch between discovered networks
         * on its own (instead of only using networks which are already connected).
         */
        WIFI_CLIENT,
        /**
         * Allows the platform to enable WLAN in AP mode, turning into an access point for other
         * neighbors to connect when there are no other means of connectivity (i.e., open networks).
         * Incurs a significant penalty on battery life.
         */
        WIFI_AP,
        /**
         * Allows the platform to enable Bluetooth to discover other neighbors. This is an intrusive
         * feature: It will prompt the user for permission to make the device discoverable. Affects
         * battery life, especially while being discoverable.
         */
        BLUETOOTH,
        /**
         * Allows the platform to perform network switching also when the screen is on. The reason
         * this is a special feature is the fact that when a user is actively using the device
         * (hence the screen is on), he usually relies on a stable WLAN connection, which the
         * platform normally can not guarantee.
         */
        FOREGROUND;
    }
}
