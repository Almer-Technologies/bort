package com.memfault.bort.receivers

import android.content.Context
import android.content.Intent
import com.memfault.bort.*
import com.memfault.bort.requester.BugReportRequester

class BootReceiver : BortEnabledFilteringReceiver(
    INTENT_ACTION_BOOT_COMPLETED
) {

    override fun onReceivedAndEnabled(context: Context, intent: Intent) {
        BugReportRequester(
            context
        ).requestPeriodic(
            settingsProvider.bugReportRequestIntervalHours(),
            settingsProvider.firstBugReportDelayAfterBootMinutes()
        )
    }
}
