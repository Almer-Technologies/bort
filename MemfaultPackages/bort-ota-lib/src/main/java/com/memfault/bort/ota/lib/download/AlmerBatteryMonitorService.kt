package com.memfault.bort.ota.lib.download

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LifecycleService
import com.memfault.bort.ota.lib.AlmerBatteryStats
import com.memfault.bort.ota.lib.R
import com.memfault.bort.ota.lib.Updater
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.Timer
import java.util.TimerTask
import javax.inject.Inject

private const val NOTIFICATION_CHANNEL_ID = "download_ota_notification"

/**
 * A foreground service that downloads an OTA update to the expected folder. It shows a progress notification
 * and will issue update actions to the updater when the download succeeds or fails.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@AndroidEntryPoint
class AlmerBatteryMonitorService : LifecycleService() {
    @Inject lateinit var updater: Updater
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationBuilder: NotificationCompat.Builder
    @Inject lateinit var almerBatteryStats: AlmerBatteryStats

    var timer = Timer()
    var batteryChecker: TimerTask = object : TimerTask() {
        override fun run() {
            if(::almerBatteryStats.isInitialized) {
                almerBatteryStats.checkBatteryStatus(applicationContext)
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        super.onStartCommand(intent, flags, startId)

        // Show a user-facing notification and start in the foreground so that the system does not kill us
        notificationManager = NotificationManagerCompat.from(this)
        notificationBuilder = setupForegroundNotification(this)
        startForeground(0x9002, notificationBuilder.build())

        val _5m: Long = 5 * 60 * 1000
        timer.scheduleAtFixedRate(batteryChecker, _5m, _5m)

        return START_STICKY
    }

    companion object {
        fun monitorBattery(
            context: Context,
        ) {
            context.startForegroundService(Intent(context, AlmerBatteryMonitorService::class.java))
        }

        private fun ensureNotificationChannel(context: Context) {
            NotificationChannelCompat.Builder(
                NOTIFICATION_CHANNEL_ID,
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setName(context.getString(R.string.software_update_download))
                setDescription(context.getString(R.string.software_update_download_description))
            }.also {
                NotificationManagerCompat.from(context)
                    .createNotificationChannel(it.build())
            }
        }

        fun setupForegroundNotification(context: Context): NotificationCompat.Builder {
            ensureNotificationChannel(context)
            return NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID).apply {
                setContentTitle(context.getString(R.string.download_notification_title))
                setSmallIcon(R.drawable.ic_baseline_cloud_download_24)
                setAutoCancel(false)
                priority = NotificationCompat.PRIORITY_LOW
            }
        }
    }
}

