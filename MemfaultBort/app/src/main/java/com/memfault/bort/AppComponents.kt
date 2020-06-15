package com.memfault.bort

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.memfault.bort.uploader.MemfaultBugReportUploader
import com.memfault.bort.uploader.PreparedUploadService
import com.memfault.bort.uploader.PreparedUploader
import com.memfault.bort.uploader.UploadWorker
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonConfiguration
import okhttp3.*
import retrofit2.Converter
import retrofit2.Retrofit
import java.lang.IllegalArgumentException

// A simple holder for application components
// This example uses a service locator pattern over a DI framework for simplicity and ease of use;
// if you would prefer to see a DI framework being used, please let us know!
data class AppComponents(
    val settingsProvider: SettingsProvider,
    val okHttpClient: OkHttpClient,
    val retrofitClient: Retrofit,
    val workerFactory: WorkerFactory,
    val fileUploaderFactory: FileUploaderFactory,
    val bortEnabledProvider: BortEnabledProvider
) {
    open class Builder(
        private val context: Context,
        private val sharedPreferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    ) {
        var settingsProvider: SettingsProvider? = null
        var networkInterceptor: Interceptor? = null
        var okHttpClient: OkHttpClient? = null
        var retrofit: Retrofit? = null
        var interceptingWorkerFactory: WorkerFactory? = null
        var fileUploaderFactory: FileUploaderFactory? = null
        var bortEnabledProvider: BortEnabledProvider? = null

        fun build(): AppComponents {
            val settingsProvider = settingsProvider ?: BuildConfigSettingsProvider()
            val fileUploaderFactory = fileUploaderFactory ?: MemfaultFileUploaderFactory()
            val okHttpClient = okHttpClient ?: OkHttpClient.Builder()
                .addInterceptor(networkInterceptor ?: LoggingNetworkInterceptor())
                .build()
            val retrofit = retrofit ?: Retrofit.Builder()
                .client(okHttpClient)
                .baseUrl(HttpUrl.get(settingsProvider.baseUrl()))
                .addConverterFactory(
                    kotlinxJsonConverterFactory()
                )
                .build()
            val bortEnabledProvider = bortEnabledProvider ?: if (settingsProvider.isRuntimeEnableRequired()) {
                PreferenceBortEnabledProvider(
                    sharedPreferences,
                    defaultValue = !settingsProvider.isRuntimeEnableRequired()
                )
            } else {
                BortAlwaysEnabledProvider()
            }

            val workerFactory = DefaultWorkerFactory(
                settingsProvider = settingsProvider,
                bortEnabledProvider = bortEnabledProvider,
                retrofit = retrofit,
                fileUploaderFactory = fileUploaderFactory,
                interceptingFactory = interceptingWorkerFactory
            )
            return AppComponents(
                settingsProvider =  settingsProvider,
                okHttpClient = okHttpClient,
                retrofitClient = retrofit,
                workerFactory = workerFactory,
                fileUploaderFactory = fileUploaderFactory,
                bortEnabledProvider = bortEnabledProvider
            )
        }
    }

    fun isEnabled(): Boolean = bortEnabledProvider.isEnabled()
}

fun kotlinxJsonConverterFactory(): Converter.Factory =
    Json(JsonConfiguration.Stable)
        .asConverterFactory(MediaType.get("application/json"))

class LoggingNetworkInterceptor: Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request: Request = chain.request()
        val t1: Long = System.nanoTime()
        Logger.v("Sending request ${request.url()}")
        val response: Response = chain.proceed(request)
        val t2: Long = System.nanoTime()
        val delta = (t2 - t1) / 1e6
        Logger.v(
            """
Received response for ${response.request().url()} in ${String.format("%.1f", delta)} ms
        """.trimEnd()
        )
        return response
    }
}

open class BuildConfigSettingsProvider: SettingsProvider {
    override fun bugReportRequestIntervalHours() =
        BuildConfig.BUG_REPORT_REQUEST_INTERVAL_HOURS.toLong()

    override fun firstBugReportDelayAfterBootMinutes(): Long =
        BuildConfig.FIRST_BUG_REPORT_DELAY_AFTER_BOOT_MINUTES.toLong()

    override fun minLogLevel(): LogLevel =
        LogLevel.fromInt(BuildConfig.MINIMUM_LOG_LEVEL) ?: LogLevel.VERBOSE

    override fun bugReportNetworkConstraint(): NetworkConstraint =
        if (BuildConfig.UPLOAD_NETWORK_CONSTRAINT_ALLOW_METERED_CONNECTION) NetworkConstraint.CONNECTED
        else NetworkConstraint.UNMETERED

    override fun maxUploadAttempts(): Int = BuildConfig.BUG_REPORT_MAX_UPLOAD_ATTEMPTS

    override fun isRuntimeEnableRequired(): Boolean = BuildConfig.RUNTIME_ENABLE_REQUIRED

    override fun projectKey(): String = BuildConfig.MEMFAULT_PROJECT_API_KEY

    override fun baseUrl(): String = BuildConfig.MEMFAULT_FILES_BASE_URL
}

class DefaultWorkerFactory(
    private val settingsProvider: SettingsProvider,
    private val bortEnabledProvider: BortEnabledProvider,
    private val retrofit: Retrofit,
    private val fileUploaderFactory: FileUploaderFactory,
    private val interceptingFactory: WorkerFactory? = null
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters
    ): ListenableWorker? {
        interceptingFactory?.createWorker(
            appContext,
            workerClassName,
            workerParameters
        )?.let {
            return it
        }

        return when (workerClassName) {
            UploadWorker::class.qualifiedName -> UploadWorker(
                appContext = appContext,
                workerParameters = workerParameters,
                settingsProvider = settingsProvider,
                bortEnabledProvider = bortEnabledProvider,
                retrofit = retrofit,
                fileUploaderFactory = fileUploaderFactory
            )
            else -> null
        }
    }
}

class MemfaultFileUploaderFactory : FileUploaderFactory {
    override fun create(retrofit: Retrofit, projectApiKey: String): FileUploader =
        MemfaultBugReportUploader(
            preparedUploader = PreparedUploader(
                retrofit.create(PreparedUploadService::class.java),
                projectApiKey
            )
        )
}

/**
 * A stub "enabled" provider; used only when the device does not require being enabled at runtime.
 */
class BortAlwaysEnabledProvider : BortEnabledProvider {
    override fun setEnabled(isOptedIn: Boolean) {
    }

    override fun isEnabled(): Boolean {
        return true
    }
}

abstract class PreferenceKeyProvider<T>(
    private val sharedPreferences: SharedPreferences,
    private val defaultValue: T,
    private val preferenceKey: String
) {
    init {
        when (defaultValue) {
            is Boolean, is String, is Int, is Long, is Float -> {}
            else -> throw IllegalArgumentException("Unsupported type $defaultValue")
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun setValue(newValue : T): Unit = with(sharedPreferences.edit()) {
        when (newValue) {
            is Boolean -> putBoolean(preferenceKey, newValue)
            is String -> putString(preferenceKey, newValue)
            is Int -> putInt(preferenceKey, newValue)
            is Long -> putLong(preferenceKey, newValue)
            is Float -> putFloat(preferenceKey, newValue)
            else -> throw IllegalArgumentException("Unsupported type $newValue")
        }
        apply()
    }

    @Suppress("UNCHECKED_CAST")
    fun getValue(): T = with(sharedPreferences) {
        return when (defaultValue) {
            is Boolean -> getBoolean(preferenceKey, defaultValue) as T
            is String -> getString(preferenceKey, defaultValue) as T
            is Int -> getInt(preferenceKey, defaultValue) as T
            is Long -> getLong(preferenceKey, defaultValue) as T
            is Float -> getFloat(preferenceKey, defaultValue) as T
            else -> throw IllegalArgumentException("Unsupported type $defaultValue")
        }
    }
}

/** A preference-backed provider of the user's opt in state. */
class PreferenceBortEnabledProvider(
    sharedPreferences: SharedPreferences,
    defaultValue: Boolean
) : PreferenceKeyProvider<Boolean>(
    sharedPreferences = sharedPreferences,
    defaultValue = defaultValue,
    preferenceKey = PREFERENCE_BORT_ENABLED
), BortEnabledProvider {
    override fun setEnabled(isOptedIn: Boolean) = setValue(isOptedIn)

    override fun isEnabled(): Boolean = getValue()
}
