package com.memfault.bort

import android.app.Application
import com.memfault.bort.fileExt.deleteSilently
import com.memfault.bort.shared.Logger
import com.squareup.anvil.annotations.ContributesBinding
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject

interface TemporaryFileFactory {
    val temporaryFileDirectory: File?
    fun createTemporaryFile(prefix: String = "tmp", suffix: String?) =
        TemporaryFile(prefix, suffix, temporaryFileDirectory)
}

@ContributesBinding(SingletonComponent::class)
class RealTemporaryFileFactory @Inject constructor(application: Application) : TemporaryFileFactory {
    override val temporaryFileDirectory: File = application.cacheDir
}

class TemporaryFile(
    val prefix: String = "tmp",
    val suffix: String? = null,
    val directory: File? = null,
) {
    inline fun <R> useFile(block: (file: File, preventDeletion: () -> Unit) -> R): R {
        @Suppress("DEPRECATION")
        val file = createTempFile(prefix, suffix, directory)
        var shouldDelete = true
        return try {
            block(file) { shouldDelete = false }
        } finally {
            if (shouldDelete && !file.deleteSilently()) {
                Logger.w("Failed to delete $file")
            }
        }
    }
}
