package com.memfault.bort.settings

import com.memfault.bort.shared.LogLevel
import com.memfault.bort.time.boxed
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals

class SettingsUpdateHandlerTest {
    private val structuredLogSettingsMock = object : StructuredLogSettings {
        override val dataSourceEnabled = true
        override val rateLimitingSettings = RateLimitingSettings(0, 0.seconds.boxed(), 0)
        override val dumpPeriod: Duration = 0.seconds
        override val numEventsBeforeDump: Long = 0
        override val maxMessageSizeBytes: Long = 0
        override val minStorageThresholdBytes: Long = 0
        override val metricsReportEnabled: Boolean = true
        override val highResMetricsEnabled: Boolean = true
    }
    private val settingsProvider: DynamicSettingsProvider = mockk {
        every { invalidate() } returns Unit
        every { structuredLogSettings } returns structuredLogSettingsMock
    }
    private val storedSettingsPreferenceProvider: StoredSettingsPreferenceProvider = mockk {
        every { set(any()) } returns Unit
        every { get() } returns SETTINGS_FIXTURE.toSettings()
    }
    private val fetchedSettingsUpdateSlot = slot<FetchedSettingsUpdate>()
    private val callback: SettingsUpdateCallback = mockk {
        coEvery { onSettingsUpdated(any(), capture(fetchedSettingsUpdateSlot)) } returns Unit
    }
    private val handler = SettingsUpdateHandler(
        settingsProvider = settingsProvider,
        storedSettingsPreferenceProvider = storedSettingsPreferenceProvider,
        settingsUpdateCallback = callback,
        metrics = mockk(relaxed = true),
    )

    @Test
    fun validResponse() = runBlocking {
        val response1 = SETTINGS_FIXTURE.toSettings()
        handler.handleSettingsUpdate(response1)

        // The first call returns the same stored fixture and thus set() won't be called
        verify {
            storedSettingsPreferenceProvider.get()
        }
        confirmVerified(storedSettingsPreferenceProvider)

        // The second one will trigger the update
        val response2 = response1.copy(bortMinLogcatLevel = LogLevel.NONE.level)
        handler.handleSettingsUpdate(response2)

        // Check that settings was invalidated after a remote update
        coVerify {
            storedSettingsPreferenceProvider.get()
            storedSettingsPreferenceProvider.set(response2)
            settingsProvider.invalidate()
            callback.onSettingsUpdated(any(), any())
            settingsProvider.structuredLogSettings
            settingsProvider.structuredLogSettings
        }
        confirmVerified(settingsProvider)
        assertEquals(fetchedSettingsUpdateSlot.captured.old, SETTINGS_FIXTURE.toSettings())
        assertEquals(fetchedSettingsUpdateSlot.captured.new, response2)
    }
}
