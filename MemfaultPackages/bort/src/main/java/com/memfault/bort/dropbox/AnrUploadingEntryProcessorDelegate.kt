package com.memfault.bort.dropbox

import android.os.DropBoxManager
import com.memfault.bort.parsers.AnrParser
import com.memfault.bort.shared.Logger
import com.memfault.bort.tokenbucket.Anr
import com.memfault.bort.tokenbucket.TokenBucketStore
import java.io.File
import javax.inject.Inject

class AnrUploadingEntryProcessorDelegate @Inject constructor(
    @Anr private val tokenBucketStore: TokenBucketStore,
) : UploadingEntryProcessorDelegate {
    override val tags = listOf(
        "data_app_anr",
        "system_app_anr",
        "system_server_anr"
    )
    override val debugTag: String
        get() = "UPLOAD_ANR"

    override fun allowedByRateLimit(tokenBucketKey: String, tag: String): Boolean =
        tokenBucketStore.allowedByRateLimit(tokenBucketKey = tokenBucketKey, tag = tag)

    override suspend fun getEntryInfo(entry: DropBoxManager.Entry, entryFile: File): EntryInfo = try {
        entryFile.inputStream().use {
            EntryInfo(entry.tag, AnrParser(it).parse().packageName)
        }
    } catch (ex: Exception) {
        Logger.w("Unable to parse ANR", ex)
        EntryInfo(entry.tag)
    }
}
