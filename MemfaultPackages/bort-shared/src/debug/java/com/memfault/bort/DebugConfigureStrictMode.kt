package com.memfault.bort

import android.os.Build
import android.os.StrictMode
import com.memfault.bort.shared.Logger
import com.squareup.anvil.annotations.ContributesBinding
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.Executors
import javax.inject.Inject

@ContributesBinding(SingletonComponent::class)
class DebugConfigureStrictMode @Inject constructor() : ConfigureStrictMode {
    override fun configure() {
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder().apply {
                detectLeakedClosableObjects()
                penaltyLog()
                // We can only use the callback on P (9) and above. On 8, crash to to hope that fails the test.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    penaltyListener(Executors.newSingleThreadExecutor()) {
                        Logger.test("Bort StrictMode violation!!")
                    }
                } else {
                    penaltyDeath()
                }
            }.build(),
        )
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().apply {
                detectNetwork()
                penaltyLog()
                // We can only use the callback on P (9) and above. On 8, crash to to hope that fails the test.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    penaltyListener(Executors.newSingleThreadExecutor()) {
                        Logger.test("Bort StrictMode violation!!")
                    }
                } else {
                    penaltyDeath()
                }
            }.build(),
        )
    }
}
