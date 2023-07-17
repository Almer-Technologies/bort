package com.memfault.bort.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.memfault.bort.ota.lib.TestOtaModePreferenceProvider
import com.memfault.bort.ota.lib.testLog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private const val INTENT_EXTRA_ECHO_STRING = "echo"
private const val INTENT_EXTRA_MODE = "mode"

/**
 * A broadcast receiver that listens to test events.
 */
@AndroidEntryPoint
class OtaTestReceiver : BroadcastReceiver() {
    @Inject lateinit var testOtaModePreferenceProvider: TestOtaModePreferenceProvider

    override fun onReceive(context: Context, intent: Intent) {
        testLog("TestReceiver action=${intent.action}")
        when (intent.action) {
            "com.memfault.intent.action.TEST_BORT_OTA_MODE" -> {
                val modeExtra = intent.getStringExtra(INTENT_EXTRA_MODE) ?: return
                testOtaModePreferenceProvider.setValue(modeExtra)
                testLog("bort-ota updater mode set to $modeExtra")
            }

            "com.memfault.intent.action.TEST_BORT_OTA_ECHO" -> {
                testLog("bort-ota echo ${intent.getStringExtra(INTENT_EXTRA_ECHO_STRING)}")
            }

            else -> {
                testLog("bort-ota unhandled action ${intent.action}")
            }
        }
    }
}
