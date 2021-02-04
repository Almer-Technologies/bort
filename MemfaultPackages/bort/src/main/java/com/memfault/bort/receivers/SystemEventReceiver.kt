package com.memfault.bort.receivers

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.preference.PreferenceManager
import com.memfault.bort.AndroidBootReason
import com.memfault.bort.BootCountTracker
import com.memfault.bort.DumpsterClient
import com.memfault.bort.PREFERENCE_TOKEN_BUCKET_REBOOT_EVENT
import com.memfault.bort.RealLastTrackedBootCountProvider
import com.memfault.bort.RebootEventUploader
import com.memfault.bort.requester.BugReportRequester
import com.memfault.bort.requester.MetricsCollectionRequester
import com.memfault.bort.shared.Logger
import com.memfault.bort.tokenbucket.RealTokenBucketFactory
import com.memfault.bort.tokenbucket.RealTokenBucketStorage
import com.memfault.bort.tokenbucket.TokenBucketStore
import kotlin.time.minutes

class SystemEventReceiver : BortEnabledFilteringReceiver(
    setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
) {

    private fun onPackageReplaced(context: Context) {
        listOf(
            MetricsCollectionRequester(context, settingsProvider.metricsSettings),
            BugReportRequester(context, settingsProvider.bugReportSettings),
        ).forEach {
            it.startPeriodic()
        }
    }

    private fun onBootCompleted(context: Context) {
        Logger.logEvent("boot")
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        goAsync {
            DumpsterClient().getprop()?.let { systemProperties ->
                val tokenBucketStore = TokenBucketStore(
                    storage = RealTokenBucketStorage(
                        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context),
                        preferenceKey = PREFERENCE_TOKEN_BUCKET_REBOOT_EVENT,
                    ),
                    maxBuckets = 1,
                    tokenBucketFactory = RealTokenBucketFactory(defaultCapacity = 5, defaultPeriod = 15.minutes),
                )

                val rebootEventUploader = RebootEventUploader(
                    ingressService = ingressService,
                    deviceInfo = deviceInfoProvider.getDeviceInfo(),
                    androidSysBootReason = systemProperties.get(AndroidBootReason.SYS_BOOT_REASON_KEY),
                    tokenBucketStore = tokenBucketStore,
                )

                val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
                BootCountTracker(
                    lastTrackedBootCountProvider = RealLastTrackedBootCountProvider(
                        sharedPreferences = sharedPreferences
                    ),
                    untrackedBootCountHandler = rebootEventUploader::handleUntrackedBootCount
                ).trackIfNeeded(bootCount)
            }
        }

        listOf(
            MetricsCollectionRequester(context, settingsProvider.metricsSettings),
            BugReportRequester(context, settingsProvider.bugReportSettings),
        ).forEach {
            it.startPeriodic(justBooted = true)
        }
    }

    override fun onReceivedAndEnabled(context: Context, intent: Intent, action: String) {
        when (action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> onPackageReplaced(context)
            Intent.ACTION_BOOT_COMPLETED -> onBootCompleted(context)
        }
    }
}
