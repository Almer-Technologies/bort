package com.memfault.bort.settings

import android.content.SharedPreferences
import com.memfault.bort.BortJson
import com.memfault.bort.PREFERENCE_FETCHED_SDK_SETTINGS
import com.memfault.bort.shared.Logger
import com.memfault.bort.shared.PreferenceKeyProvider
import kotlinx.serialization.SerializationException

private const val INVALID_MARKER = "__NA__"

interface StoredSettingsPreferenceProvider {
    fun get(): FetchedSettings
    fun set(settings: FetchedSettings)
}

class RealStoredSettingsPreferenceProvider(
    sharedPreferences: SharedPreferences,
    private val getBundledConfig: () -> String,
) : StoredSettingsPreferenceProvider, PreferenceKeyProvider<String>(
    sharedPreferences = sharedPreferences,
    defaultValue = INVALID_MARKER,
    PREFERENCE_FETCHED_SDK_SETTINGS,
) {
    override fun get(): FetchedSettings {
        val content = super.getValue()
        return if (content == INVALID_MARKER) {
            FetchedSettings.from(getBundledConfig()) { BortJson }
        } else try {
            FetchedSettings.from(content) { BortJson }
        } catch (ex: SerializationException) {
            Logger.w("Unable to deserialize settings, falling back to bundled config", ex)
            FetchedSettings.from(getBundledConfig()) { BortJson }
        }
    }

    override fun set(settings: FetchedSettings) {
        super.setValue(
            BortJson.encodeToString(
                FetchedSettings.FetchedSettingsContainer.serializer(),
                FetchedSettings.FetchedSettingsContainer(settings)
            )
        )
    }
}
