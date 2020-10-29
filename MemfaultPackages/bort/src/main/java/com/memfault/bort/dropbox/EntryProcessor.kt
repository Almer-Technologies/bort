package com.memfault.bort.dropbox

import android.os.DropBoxManager

abstract class EntryProcessor {
    abstract val tags: List<String>

    abstract fun process(entry: DropBoxManager.Entry)

    fun tagPairs() = tags.map { tag -> tag to this }.toTypedArray()
}