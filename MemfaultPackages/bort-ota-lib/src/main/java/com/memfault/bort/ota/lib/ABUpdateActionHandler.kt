package com.memfault.bort.ota.lib

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.UpdateEngine
import android.os.UpdateEngineCallback
import android.util.Log
import com.memfault.bort.shared.BortSharedJson
import com.memfault.bort.shared.BuildConfig
import com.memfault.bort.shared.Logger
import com.memfault.bort.shared.PreferenceKeyProvider
import com.squareup.anvil.annotations.ContributesBinding
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

fun interface RebootDevice : () -> Unit

@ContributesBinding(SingletonComponent::class)
class RealRebootDevice @Inject constructor(
    private val application: Application,
) : RebootDevice {
    override fun invoke() {
        application.getSystemService(PowerManager::class.java)
            .reboot(null)
    }
}

@Singleton
class ABUpdateActionHandler @Inject constructor(
    private val androidUpdateEngine: AndroidUpdateEngine,
    private val rebootDevice: RebootDevice,
    private val cachedOtaProvider: CachedOtaProvider,
    private val updater: Updater,
    private val scheduleDownload: ScheduleDownload,
    private val application: Application,
    private val otaRulesProvider: OtaRulesProvider,
    private val softwareUpdateChecker: SoftwareUpdateChecker,
    private val settingsProvider: SoftwareUpdateSettingsProvider,
    private val almerBatteryStats: AlmerBatteryStats
) : UpdateActionHandler {
    override fun initialize() {
        androidUpdateEngine.bind(object : AndroidUpdateEngineCallback {
            override fun onStatusUpdate(status: Int, percent: Float) {
                Logger.d("onStatusUpdate: status=$status percent=$percent")
                CoroutineScope(Dispatchers.Default).launch {
                    when (status) {
                        UPDATE_ENGINE_STATUS_DOWNLOADING -> {
                            val cachedOta = cachedOtaProvider.get()
                            if (cachedOta != null) {
                                updater.setState(State.UpdateDownloading(cachedOta, (percent * 100).toInt()))
                            } else {
                                updater.setState(State.Idle)
                            }
                        }

                        UPDATE_ENGINE_FINALIZING -> {
                            val cachedOta = cachedOtaProvider.get()
                            if (cachedOta != null) {
                                updater.setState(State.Finalizing(cachedOta, (percent * 100).toInt()))
                            } else {
                                updater.setState(State.Idle)
                            }
                        }

                        UPDATE_ENGINE_STATUS_IDLE ->
                            updater.setState(State.Idle)

                        UPDATE_ENGINE_REPORTING_ERROR_EVENT ->
                            updater.setState(State.Idle) // errors are reported in the method below
                        UPDATE_ENGINE_UPDATED_NEED_REBOOT -> {
                            val cachedOta = cachedOtaProvider.get()
                            if (cachedOta != null) {
                                updater.setState(State.RebootNeeded(cachedOta))
                                val scheduleAutoInstall = BuildConfig.OTA_AUTO_INSTALL || (cachedOta.isForced == true)
                                if (scheduleAutoInstall) {
                                    OtaInstallWorker.schedule(application, otaRulesProvider, cachedOta)
                                }
                            } else {
                                updater.setState(State.Idle)
                            }
                        }
                        // Note: this is not used in Android
                        UPDATE_ENGINE_STATUS_UPDATE_AVAILABLE -> {}
                        // Note: this is not used in streaming updates
                        UPDATE_ENGINE_STATUS_VERIFYING -> {}
                        // Note: this is not used in Android
                        UPDATE_ENGINE_STATUS_CHECKING_FOR_UPDATE -> {}
                        // Note: This is handled by the error callbacks below
                        UPDATE_ENGINE_ATTEMPTING_ROLLBACK ->
                            Log.v("Updater", "Attempting rollback $percent")
                        // Note: This is handled by the error callbacks below
                        UPDATE_ENGINE_DISABLED ->
                            Log.v("Updater", "Disabled $percent")
                    }
                }
            }

            override fun onPayloadApplicationComplete(errorCode: Int) {
                Logger.d("onPayloadApplicationComplete: errorCode=$errorCode")
                CoroutineScope(Dispatchers.Default).launch {
                    when (errorCode) {
                        UPDATE_ENGINE_ERROR_SUCCESS -> {}
                        UPDATE_ENGINE_ERROR_DOWNLOAD_TRANSFER_ERROR ->
                            updater.triggerEvent(Event.DownloadFailed)

                        else ->
                            updater.triggerEvent(Event.VerificationFailed)
                    }
                }
            }
        })
    }

    override suspend fun handle(
        state: State,
        action: Action,
    ) {
        fun logActionNotAllowed() = Logger.i("Action $action not allowed in state $state")

        when (action) {
            is Action.CheckForUpdate -> {
                if (state.allowsUpdateCheck()) {
                    updater.setState(State.CheckingForUpdates)
                    val ota = softwareUpdateChecker.getLatestRelease()
                    if (ota == null) {
                        updater.setState(State.Idle)
                    } else {
                        cachedOtaProvider.set(ota)
                        handleUpdateAvailable(
                            updater = updater,
                            ota = ota,
                            action = action,
                            scheduleDownload = scheduleDownload,
                        )
                    }
                } else {
                    logActionNotAllowed()
                }
            }

            is Action.DownloadUpdate -> {
                if (state is State.UpdateAvailable) {
                    androidUpdateEngine.applyPayload(
                        state.ota.url,
                        state.ota.artifactMetadata["_MFLT_PAYLOAD_OFFSET"]?.toLong() ?: 0L,
                        state.ota.artifactMetadata["_MFLT_PAYLOAD_SIZE"]?.toLong() ?: 0L,
                        state.ota.artifactMetadata.map {
                            "${it.key}=${it.value}"
                        }.toTypedArray(),
                    )
                } else {
                    logActionNotAllowed()
                }
            }

            is Action.Reboot -> {
                val ota = cachedOtaProvider.get()
                if (state is State.RebootNeeded && ota != null) {
                    updater.setState(
                        State.RebootedForInstallation(
                            ota,
                            updatingFromVersion = settingsProvider.get().currentVersion,
                        ),
                    )
                    //rebootDevice()
                    almerRebootDevice(ota)
                } else {
                    Logger.i("Action $action not allowed in state $state (ota = $ota)")
                    updater.setState(State.Idle)
                }
            }

            else -> {
                Logger.w("Unhandled action: $action")
            }
        }
    }

    private fun almerRebootDevice(ota: Ota?) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.i("AlmerOTA", "No reboot for Android 9")
                return
            }
            //For force ota, we should reboot always
            if (ota?.releaseMetadata?.containsKey("minBuildUtc")!!) {
                val minVer: String = ota.releaseMetadata.getOrElse("minBuildUtc") { "0" }
                if (Build.TIME < minVer.toLong()) {
                    rebootDevice()
                    return
                }
            }

            //Check battery
            val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
                application.registerReceiver(null, ifilter)
            }

            val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

            if (!isCharging || !almerBatteryStats.isPluggedInOverHour()) {
                Log.i(
                    "AlmerOTA",
                    "Skipping auto reboot after OTA as battery status is not as expected. Is Charging: $isCharging, Level: ${almerBatteryStats.isPluggedInOverHour()}"
                )
                return
            }

            //Check time..
            val now = LocalDateTime.now()
            val _3am = now.withHour(3).withMinute(0).withSecond(0)
            val _5am = now.withHour(5).withMinute(0).withSecond(0)

            if (now.isBefore(_3am) || now.isAfter(_5am)) {
                Log.i("AlmerOTA", "Skiping auto reboot after OTA as time is not between 3am and 5am")
                return
            }

            //All conditions are OK, let's reboot!
            rebootDevice()


        } catch (e: Exception) {
            e.printStackTrace()
            Log.i("AlmerOTA", "Exception occurred during reboot. Doing nothing")
        }
    }
}

interface CachedOtaProvider {
    fun get(): Ota?
    fun set(ota: Ota?)
}

@ContributesBinding(SingletonComponent::class, boundType = CachedOtaProvider::class)
class SharedPreferenceCachedOtaProvider @Inject constructor(sharedPreferences: SharedPreferences) :
    PreferenceKeyProvider<String>(sharedPreferences, EMPTY, CACHED_OTA_KEY), CachedOtaProvider {
    override fun get(): Ota? {
        val stored = getValue()
        return if (stored != EMPTY) {
            BortSharedJson.decodeFromString(Ota.serializer(), stored)
        } else {
            null
        }
    }

    override fun set(ota: Ota?) {
        if (ota == null) {
            setValue(EMPTY)
        } else {
            setValue(BortSharedJson.encodeToString(Ota.serializer(), ota))
        }
    }

    companion object {
        private const val EMPTY = ""
    }
}

// These must match platform values
const val UPDATE_ENGINE_STATUS_IDLE = 0
const val UPDATE_ENGINE_STATUS_CHECKING_FOR_UPDATE = 1
const val UPDATE_ENGINE_STATUS_UPDATE_AVAILABLE = 2
const val UPDATE_ENGINE_STATUS_DOWNLOADING = 3
const val UPDATE_ENGINE_STATUS_VERIFYING = 4
const val UPDATE_ENGINE_FINALIZING = 5
const val UPDATE_ENGINE_UPDATED_NEED_REBOOT = 6
const val UPDATE_ENGINE_REPORTING_ERROR_EVENT = 7
const val UPDATE_ENGINE_ATTEMPTING_ROLLBACK = 8
const val UPDATE_ENGINE_DISABLED = 9

// These two are relevant to our use cases, all others are verification errors (i.e. checksums, device state, etc).
const val UPDATE_ENGINE_ERROR_SUCCESS = 0
const val UPDATE_ENGINE_ERROR_DOWNLOAD_TRANSFER_ERROR = 9

interface AndroidUpdateEngine {
    fun bind(callback: AndroidUpdateEngineCallback)
    fun applyPayload(url: String, offset: Long, size: Long, metadata: Array<String>)
}

interface AndroidUpdateEngineCallback {
    fun onStatusUpdate(status: Int, percent: Float)
    fun onPayloadApplicationComplete(errorCode: Int)
}

@ContributesBinding(SingletonComponent::class)
@Singleton
class RealAndroidUpdateEngine @Inject constructor() : AndroidUpdateEngine {
    private val updateEngine = UpdateEngine()

    override fun bind(callback: AndroidUpdateEngineCallback) {
        updateEngine.bind(object : UpdateEngineCallback() {
            override fun onStatusUpdate(status: Int, percent: Float) {
                callback.onStatusUpdate(status, percent)
            }

            override fun onPayloadApplicationComplete(errorCode: Int) {
                callback.onPayloadApplicationComplete(errorCode)
            }
        })
    }

    override fun applyPayload(
        url: String,
        offset: Long,
        size: Long,
        metadata: Array<String>,
    ) {
        updateEngine.applyPayload(
            url,
            offset,
            size,
            metadata,
        )
    }
}
