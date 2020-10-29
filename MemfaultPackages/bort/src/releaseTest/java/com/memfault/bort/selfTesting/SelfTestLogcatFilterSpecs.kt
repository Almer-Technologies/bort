package com.memfault.bort.selfTesting

import android.util.Log
import com.memfault.bort.ReporterServiceConnector
import com.memfault.bort.shared.LogcatBufferId
import com.memfault.bort.shared.LogcatCommand
import com.memfault.bort.shared.LogcatFilterSpec
import com.memfault.bort.shared.LogcatFormat
import com.memfault.bort.shared.LogcatFormatModifier
import com.memfault.bort.shared.LogcatPriority
import com.memfault.bort.shared.Logger
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SelfTestLogcatFilterSpecs(val reporterServiceConnector: ReporterServiceConnector) : SelfTester.Case {
    override suspend fun test(): Boolean {
        val verboseLog = UUID.randomUUID().toString().also {
            Logger.v(it)
        }
        val debugLog = UUID.randomUUID().toString().also {
            Logger.d(it)
        }
        val ignoredTag = UUID.randomUUID().toString().also {
            Log.i(it, "Ignored!")
        }
        val fatalLog = UUID.randomUUID().toString().also {
            Log.e(it, "Error!")
        }
        return reporterServiceConnector.testLogcatRun(
            LogcatCommand(
                filterSpecs = listOf(
                    // Allow logs of DEBUG or higher from Logger.TAG:
                    LogcatFilterSpec(tag = Logger.TAG, priority = LogcatPriority.DEBUG),
                    // Allow logs of ERROR or higher from any other tag:
                    LogcatFilterSpec(priority = LogcatPriority.ERROR)
                )
            )
        ) { output ->
            val text = output.toString(Charsets.UTF_8)
            text.contains(debugLog) and
                text.contains(fatalLog) and
                !text.contains(verboseLog) and
                !text.contains(ignoredTag)
        }
    }
}

class SelfTestLogcatFormat(val reporterServiceConnector: ReporterServiceConnector) : SelfTester.Case {
    override suspend fun test(): Boolean {
        val testLog = UUID.randomUUID().toString().also {
            Logger.test(it)
        }
        return reporterServiceConnector.testLogcatRun(
            LogcatCommand(
                format = LogcatFormat.THREADTIME,
                formatModifiers = listOf(
                    LogcatFormatModifier.UTC,
                    LogcatFormatModifier.USEC,
                    LogcatFormatModifier.UID
                )
            )
        ) { output ->
            val text = output.toString(Charsets.UTF_8)
            // Example:
            // 10-20 12:20:28.940622 +0000 10014  2518  2615 V bort-test: b78df7d1-42ba-4387-9056-975c4081ceb6
            val pattern = Regex(
                """\d+-\d+\s\d+:\d+:\d+\.\d+\s+\+0000\s+[0-9_au]+\s+\d+\s+\d+\s+V\s+bort-test:\s+$testLog"""
            )
            pattern.containsMatchIn(text)
        }
    }
}

class SelfTestLogcatCommandSerialization : SelfTester.Case {
    // This test the serialization of LogcatCommand.
    // Bundle / Parcel can't be instantiated in unit tests, therefore it's tested here.
    override suspend fun test(): Boolean =
        listOf(
            LogcatCommand(
                filterSpecs = listOf(
                    LogcatFilterSpec(tag = "test", priority = LogcatPriority.DEBUG)
                )
            ),
            LogcatCommand(
                format = LogcatFormat.BRIEF
            ),
            LogcatCommand(
                formatModifiers = listOf(
                    LogcatFormatModifier.UID
                )
            ),
            LogcatCommand(dividers = true),
            LogcatCommand(clear = true),
            LogcatCommand(dumpAndExit = true),
            LogcatCommand(maxCount = 123),
            LogcatCommand(recentCount = 123),
            LogcatCommand(
                recentSince = LocalDateTime.ofEpochSecond(
                    1234, 1234, ZoneOffset.UTC
                )
            ),
            LogcatCommand(getBufferSize = true),
            LogcatCommand(buffers = listOf(LogcatBufferId.CRASH, LogcatBufferId.EVENTS)),
            LogcatCommand(last = true),
            LogcatCommand(binary = true),
            LogcatCommand(statistics = true),
            LogcatCommand(getPrune = true),
            LogcatCommand(wrap = true),
            LogcatCommand(help = true)
        ).map { input ->
            val output = LogcatCommand.fromBundle(input.toBundle())
            (output == input).also { equal ->
                if (!equal) {
                    Logger.e("Serialization failed! $output != $input")
                }
            }
        }.all { it }
}

private suspend fun ReporterServiceConnector.testLogcatRun(
    cmd: LogcatCommand,
    block: (ByteArray) -> Boolean
): Boolean =
    this.connect { getClient ->
        getClient().logcatRun(cmd) { stream ->
            stream ?: return@logcatRun false.also {
                Logger.e("Logcat stream was null")
            }
            withContext(Dispatchers.IO) {
                val output = stream.readBytes()
                block(output).also { success ->
                    if (!success) {
                        Logger.d("Logcat text:")
                        for (line in output.toString(Charsets.UTF_8).lines()) {
                            Logger.d(line)
                        }
                    }
                }
            }
        }
    }