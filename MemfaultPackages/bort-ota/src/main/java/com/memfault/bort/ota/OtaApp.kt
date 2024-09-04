package com.memfault.bort.ota

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.memfault.bort.ota.lib.OtaLoggerSettings
import com.memfault.bort.ota.lib.UpdateActionHandler
import com.memfault.bort.ota.lib.Updater
import com.memfault.bort.reporting.Reporting
import com.memfault.bort.reporting.StateAgg.LATEST_VALUE
import com.memfault.bort.scopes.RootScopeBuilder
import com.memfault.bort.shared.BuildConfig
import com.memfault.bort.shared.LogLevel
import com.memfault.bort.shared.Logger
import com.memfault.bort.shared.LoggerSettings
import com.memfault.bort.shared.disableAppComponents
import com.memfault.bort.shared.isPrimaryUser
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.system.exitProcess

@HiltAndroidApp
class OtaApp : Application(), Configuration.Provider, Runnable {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var updater: Updater

    @Inject lateinit var actionHandler: UpdateActionHandler

    @Inject lateinit var otaLoggerSettings: OtaLoggerSettings

    @Inject lateinit var rootScopeBuilder: RootScopeBuilder

    override fun onCreate() {
        super.onCreate()

        Logger.initTags(tag = "bort-ota", testTag = "bort-ota-test")
        Logger.initSettings(
            LoggerSettings(
                eventLogEnabled = true,
                logToDisk = false,
                minLogcatLevel = otaLoggerSettings.minLogcatLevel,
                minStructuredLevel = LogLevel.INFO,
                hrtEnabled = false,
            ),
        )

        if (!isPrimaryUser()) {
            Logger.w("bort-ota disabled for secondary user")
            disableAppComponents(applicationContext)
            exitProcess(0)
        }

        rootScopeBuilder.onCreate("ota-root")

        val autoInstallMetric = Reporting.report()
            .boolStateTracker(name = "ota_auto_install", aggregations = listOf(LATEST_VALUE), internal = true)
        autoInstallMetric.state(BuildConfig.OTA_AUTO_INSTALL)

        actionHandler.initialize()

        //Start this one to check if the OTA should be checked.
        if (!isSetupCompleted) {
            handler.postDelayed(this, 10000)
        }

    }

    override fun onTerminate() {
        rootScopeBuilder.onTerminate()
        super.onTerminate()
    }

    override val workManagerConfiguration: Configuration get() =
        Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    //Almer changes...

    val ALMER_OTA_PREFS = "almer_ota_prefs"
    lateinit var components: AppComponents
    private lateinit var appStateListenerJob: Job
    private lateinit var eventListenerJob: Job
    private val handler = Handler(Looper.getMainLooper())

    override fun run() {
        if (isSetupCompleted) {
            checkOta()
        } else {
            handler.postDelayed(this, 10000)
        }
    }

    private fun checkOta() {
        CoroutineScope(Dispatchers.Default).launch {
            Logger.w("Update check requested via handler")
            with(updater) {
                perform(com.memfault.bort.ota.lib.Action.CheckForUpdate(background = true))
            }
        }
    }

    private val isSetupCompleted: Boolean
        get() = Settings.Secure.getInt(contentResolver, "user_setup_complete", 0) != 0

    fun launchForceUpdateUI() {
        //As per Confluence document: For Android 9 we remove the forced update altogether since it will soon be obsolete.
        //https://almer-ar.atlassian.net/wiki/spaces/Software/pages/155549754/OS+Update+behaviour
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.i("AlmerOTA", "No Force OTA screen for Android 9")
            return
        }

        val intent = Intent(this, UpdateActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
        startActivity(intent)
    }
}
