package com.memfault.bort.ota.lib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.util.Log
import com.memfault.bort.ota.lib.download.AlmerBatteryMonitorService
import com.memfault.bort.shared.BuildConfig
import com.memfault.bort.shared.INTENT_ACTION_OTA_SETTINGS_CHANGED
import com.memfault.bort.shared.InternalMetric
import com.memfault.bort.shared.InternalMetric.Companion.OTA_BOOT_COMPLETED
import com.memfault.bort.shared.InternalMetric.Companion.OTA_REBOOT_UPDATE_ERROR
import com.memfault.bort.shared.InternalMetric.Companion.OTA_REBOOT_UPDATE_SUCCESS
import com.memfault.bort.shared.InternalMetric.Companion.sendMetric
import com.memfault.bort.shared.Logger
import com.memfault.bort.shared.goAsync
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject

/**
 * This receiver ensures that the initial state of the updater is correctly set once the device boots. It deletes
 * the previous update file if it exists, schedules jobs and sets the updater state to idle.
 *
 * Note: this class is referred to by fully qualified name in Constants.OTA_RECEIVER_CLASS in bort-shared.
 */
@AndroidEntryPoint
class BootCompleteReceiver : BroadcastReceiver() {
    @Inject lateinit var updaterSettingsProvider: SoftwareUpdateSettingsProvider

    @Inject lateinit var updater: Updater

    @Inject lateinit var almerBatteryStats: AlmerBatteryStats

    override fun onReceive(context: Context, intent: Intent?) {
        almerBatteryStats.checkBatteryStatus(context)
        if (Intent.ACTION_BOOT_COMPLETED == intent?.action) {
            onBootComplete(context)
        } else if (INTENT_ACTION_OTA_SETTINGS_CHANGED == intent?.action) {
            onSettingsUpdated(context)
        }
    }

    private fun onBootComplete(context: Context) {
        deleteUpdateFileIfExists()
        context.sendMetric(InternalMetric(key = OTA_BOOT_COMPLETED))
        Logger.i(
            TAG_BOOT_COMPLETED,
            mapOf(
                PARAM_APP_VERSION_NAME to BuildConfig.APP_VERSION_NAME,
                PARAM_APP_VERSION_CODE to BuildConfig.APP_VERSION_CODE,
            ),
        )

        PeriodicSoftwareUpdateWorker.schedule(context, updaterSettingsProvider)
        goAsync {
            when (val currentState = updater.updateState.first()) {
                // When booting from this state, a reboot to update was requested. If the version changed, report this
                // as a success, otherwise as an error.
                is State.RebootedForInstallation -> {
                    val updateSuccessful =
                        currentState.updatingFromVersion != updaterSettingsProvider.get().currentVersion
                    context.sendMetric(
                        InternalMetric(
                            key = if (updateSuccessful) OTA_REBOOT_UPDATE_SUCCESS else OTA_REBOOT_UPDATE_ERROR,
                        ),
                    )
                    // Emit actions if clients want to let users know about update failures
                    updater.triggerEvent(
                        if (updateSuccessful) {
                            Event.RebootToUpdateSucceeded
                        } else {
                            Event.RebootToUpdateFailed
                        },
                    )
                }

                else -> {}
            }
            updater.setState(State.Idle)


            //Check only if user setup is completed.
            if ((context.getSystemService(Context.USER_SERVICE) as UserManager).isUserUnlocked) {
                Log.i("AlmerOTA", "Triggering check for update on Boot")
                val updater = updater
                updater.perform(Action.CheckForUpdate(background = true))
                updater.updateState.first { it != State.CheckingForUpdates }
            }
        }
    }

    private fun onSettingsUpdated(context: Context) {
        updaterSettingsProvider.update()
        PeriodicSoftwareUpdateWorker.schedule(context, updaterSettingsProvider)
    }

    private fun deleteUpdateFileIfExists() {
        File(OTA_PATH).let { if (it.exists()) it.delete() }
    }
}
