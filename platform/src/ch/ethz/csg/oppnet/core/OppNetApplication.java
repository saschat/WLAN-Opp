
package ch.ethz.csg.oppnet.core;

import android.app.Application;
import android.util.Log;

import ch.ethz.csg.oppnet.apps.AppRegistrationService;
import ch.ethz.csg.oppnet.apps.ApplicationRegistry;
import ch.ethz.csg.oppnet.apps.ProtocolRegistry;
import ch.ethz.csg.oppnet.crypto.CryptoHelper;
import ch.ethz.csg.oppnet.crypto.MasterKeyUtil;
import ch.ethz.csg.oppnet.crypto.PRNGFixes;
import ch.ethz.csg.oppnet.data.ConfigurationStore;
import ch.ethz.csg.oppnet.data.DbCleanupTasks;
import ch.ethz.csg.oppnet.exchange.PacketRegistry;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OppNetApplication extends Application {
    private static final String TAG = OppNetApplication.class.getSimpleName();

    private ScheduledExecutorService mAsyncExecutorService;
    private Thread.UncaughtExceptionHandler mOldDefaultHandler;

    public ApplicationRegistry applicationRegistry;
    public ProtocolRegistry protocolRegistry;
    public PacketRegistry packetRegistry;

    @Override
    public void onCreate() {
        PRNGFixes.apply();
        Log.v(TAG, "Native code is included correctly? " + CryptoHelper.testNativeLibrary());

        SupervisorService.startSupervisorService(this);

        applicationRegistry = ApplicationRegistry.getInstance(this);
        protocolRegistry = ProtocolRegistry.getInstance(this);
        packetRegistry = PacketRegistry.getInstance(this);

        mAsyncExecutorService = Executors.newScheduledThreadPool(2);
        mAsyncExecutorService.scheduleAtFixedRate(
                new DbCleanupTasks.ExpiredPacketsCleanupTask(this),
                1, 30, TimeUnit.MINUTES);

        if (ConfigurationStore.isFirstRun(this)) {
            // Create initial master identity
            MasterKeyUtil.create(this);

            // Issue API keys for existing OppNet client apps
            AppRegistrationService.startBulkIssueApiKeys(this);

            // Save default policy
            ConfigurationStore.saveCurrentPolicy(this, Policy.DEFAULT_POLICY);
        }

        mOldDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Thread.setDefaultUncaughtExceptionHandler(mOldDefaultHandler);
                try {
                    Log.e(TAG, "Unhandled error!", e);

                    if (mOldDefaultHandler != null) {
                        mOldDefaultHandler.uncaughtException(t, e);
                    }
                } catch (Throwable fatal) {
                    if (mOldDefaultHandler != null) {
                        mOldDefaultHandler.uncaughtException(t, e);
                    }
                }
            }
        });
        super.onCreate();
    }
}
