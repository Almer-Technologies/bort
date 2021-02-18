package com.memfault.bort.dropbox

import android.os.DropBoxManager
import com.memfault.bort.DeviceInfoProvider
import com.memfault.bort.DropBoxEntryFileUploadMetadata
import com.memfault.bort.DropBoxEntryFileUploadPayload
import com.memfault.bort.TemporaryFileFactory
import com.memfault.bort.logcat.NextLogcatCidProvider
import com.memfault.bort.metrics.BuiltinMetricsStore
import com.memfault.bort.metrics.DROP_BOX_TRACES_DROP_COUNT
import com.memfault.bort.metrics.metricForTraceTag
import com.memfault.bort.time.AbsoluteTime
import com.memfault.bort.time.BaseBootRelativeTime
import com.memfault.bort.time.BootRelativeTime
import com.memfault.bort.time.BootRelativeTimeProvider
import com.memfault.bort.time.toAbsoluteTime
import com.memfault.bort.tokenbucket.TokenBucketStore
import com.memfault.bort.uploader.EnqueueFileUpload
import java.io.File

interface UploadingEntryProcessorDelegate {
    val tags: List<String>

    val debugTag: String

    suspend fun createMetadata(
        tempFile: File,
        tag: String,
        fileTime: AbsoluteTime?,
        entryTime: AbsoluteTime,
        collectionTime: BootRelativeTime
    ): DropBoxEntryFileUploadMetadata

    fun getTokenBucketKey(entry: DropBoxManager.Entry, entryFile: File): String = entry.tag

    fun isTraceEntry(entry: DropBoxManager.Entry): Boolean = true
}

class UploadingEntryProcessor(
    private val delegate: UploadingEntryProcessorDelegate,
    private val tempFileFactory: TemporaryFileFactory,
    private val enqueueFileUpload: EnqueueFileUpload,
    private val nextLogcatCidProvider: NextLogcatCidProvider,
    private val bootRelativeTimeProvider: BootRelativeTimeProvider,
    private val deviceInfoProvider: DeviceInfoProvider,
    private val tokenBucketStore: TokenBucketStore,
    private val builtinMetricsStore: BuiltinMetricsStore,
    private val handleEventOfInterest: (eventTime: BaseBootRelativeTime) -> Unit,
) : EntryProcessor() {
    override val tags: List<String>
        get() = delegate.tags

    private fun allowedByRateLimit(entry: DropBoxManager.Entry, entryFile: File): Boolean =
        tokenBucketStore.edit { map ->
            val bucket = map.upsertBucket(delegate.getTokenBucketKey(entry, entryFile)) ?: return@edit false
            bucket.take()
        }

    override suspend fun process(entry: DropBoxManager.Entry, fileTime: AbsoluteTime?) {
        tempFileFactory.createTemporaryFile(entry.tag, ".txt").useFile { tempFile, preventDeletion ->
            tempFile.outputStream().use { outStream ->
                entry.inputStream.use {
                    inStream ->
                    inStream.copyTo(outStream)
                }
            }

            builtinMetricsStore.increment(metricForTraceTag(entry.tag))

            if (!allowedByRateLimit(entry, tempFile)) {
                builtinMetricsStore.increment(DROP_BOX_TRACES_DROP_COUNT)
                return
            }

            val deviceInfo = deviceInfoProvider.getDeviceInfo()
            val now = bootRelativeTimeProvider.now()
            enqueueFileUpload(
                tempFile,
                DropBoxEntryFileUploadPayload(
                    hardwareVersion = deviceInfo.hardwareVersion,
                    deviceSerial = deviceInfo.deviceSerial,
                    softwareVersion = deviceInfo.softwareVersion,
                    cidReference = nextLogcatCidProvider.cid,
                    metadata = delegate.createMetadata(
                        tempFile,
                        entry.tag,
                        fileTime,
                        entry.timeMillis.toAbsoluteTime(),
                        now,
                    )
                ),
                delegate.debugTag,
            )

            preventDeletion()

            // Only consider trace entries as "events of interest" for log collection purposes:
            if (delegate.isTraceEntry(entry)) {
                handleEventOfInterest(now)
            }
        }
    }
}
