package com.memfault.bort.ota.lib

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.util.Log
import com.memfault.bort.shared.PreferenceKeyProvider
import javax.inject.Inject
import javax.inject.Singleton

private const val PLUGGED_IN_TIME = "pluggedInTime"

@Singleton
class AlmerBatteryStats @Inject constructor(private val sharedPreferences: SharedPreferences) : PreferenceKeyProvider<Long>(
    sharedPreferences = sharedPreferences,
    defaultValue = 0L,
    preferenceKey = PLUGGED_IN_TIME,
) {

    fun isPluggedInOverHour() = System.currentTimeMillis() - getValue() > AlarmManager.INTERVAL_HOUR

    fun updatePluggedInTime(charging: Boolean) {
        if (!charging) {
            remove()
            Log.i("AlmerOTA", "Not charging")
            return
        }
        if ((sharedPreferences.getLong(PLUGGED_IN_TIME, 0L) == 0L)) {
            setValue(System.currentTimeMillis())
        }

        Log.i(
            "AlmerOTA",
            "Battery Plugged in time ${getValue()} ... Hour? : ${isPluggedInOverHour()}" )
    }

    fun checkBatteryStatus(context: Context) {
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            context.registerReceiver(null, ifilter)
        }

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging: Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING
        updatePluggedInTime(isCharging)
    }
}
