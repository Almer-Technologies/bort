package com.memfault.bort.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

suspend fun Context.registerForIntents(
    vararg actions: String,
): Flow<Intent> {
    check(actions.isNotEmpty()) { "Must provide a non-empty list of actions to listen to." }

    return callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                check(intent.action in actions)
                trySend(intent)
            }
        }

        val filter = IntentFilter()
        actions.forEach { filter.addAction(it) }

        registerReceiver(receiver, filter)

        awaitClose { unregisterReceiver(receiver) }
    }
}
