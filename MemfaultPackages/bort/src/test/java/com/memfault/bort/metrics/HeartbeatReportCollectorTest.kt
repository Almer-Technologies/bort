package com.memfault.bort.metrics

import com.memfault.bort.dropbox.MetricReport
import com.memfault.bort.dropbox.MetricReportWithHighResFile
import com.memfault.bort.settings.StructuredLogSettings
import java.io.File
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class HeartbeatReportCollectorTest {
    private var metricReportEnabledSetting = true
    private var highResMetricsEnabledSetting = true
    private val settings = object : StructuredLogSettings {
        override val dataSourceEnabled get() = TODO("Not used")
        override val rateLimitingSettings get() = TODO("Not used")
        override val dumpPeriod get() = TODO("Not used")
        override val numEventsBeforeDump get() = TODO("Not used")
        override val maxMessageSizeBytes get() = TODO("Not used")
        override val minStorageThresholdBytes get() = TODO("Not used")
        override val metricsReportEnabled get() = metricReportEnabledSetting
        override val highResMetricsEnabled get() = highResMetricsEnabledSetting
    }
    private var finishReportSuccess = true
    private val reportFinisher = object : ReportFinisher {
        override fun finishHeartbeat(): Boolean {
            sendResults()
            return finishReportSuccess
        }
    }
    private val report = MetricReport(
        version = 1,
        startTimestampMs = 2,
        endTimestampMs = 3,
        reportType = "4",
        metrics = mapOf(),
        internalMetrics = mapOf(),
    )
    private val collector = HeartbeatReportCollector(settings, reportFinisher)
    private var sendReport = false
    private var sendHighRes = false
    private var reverseOrder = false

    private fun sendResults() {
        if (sendReport && !reverseOrder) collector.handleFinishedHeartbeatReport(report)
        if (sendHighRes) collector.handleHighResMetricsFile(highResFile)
        if (sendReport && reverseOrder) collector.handleFinishedHeartbeatReport(report)
    }

    @get:Rule
    private val tempFolder = TemporaryFolder.builder().assureDeletion().build()
    private lateinit var highResFile: File

    @Before
    fun setup() {
        tempFolder.create()
        highResFile = tempFolder.newFile("high_res")
    }

    @Test
    fun timesOutWhenNoReportOrHighResFile() {
        metricReportEnabledSetting = true
        highResMetricsEnabledSetting = true
        finishReportSuccess = true
        sendReport = false
        sendHighRes = false
        runTest {
            val result = collector.finishAndCollectHeartbeatReport(timeout = 1.seconds)
            assertNull(result)
        }
    }

    @Test
    fun timesOutWhenNoReport() {
        metricReportEnabledSetting = true
        highResMetricsEnabledSetting = true
        finishReportSuccess = true
        sendReport = false
        sendHighRes = true
        runTest {
            val result = collector.finishAndCollectHeartbeatReport(timeout = 1.seconds)
            assertNull(result)
            assertFalse(highResFile.exists())
        }
    }

    @Test
    fun successWithHighRes() {
        metricReportEnabledSetting = true
        highResMetricsEnabledSetting = true
        finishReportSuccess = true
        sendReport = true
        sendHighRes = true
        runTest {
            val result = collector.finishAndCollectHeartbeatReport(timeout = 1.seconds)
            assertEquals(MetricReportWithHighResFile(report, highResFile), result)
            assertTrue(highResFile.exists())
        }
    }

    @Test
    fun successWithHighRes_reverseOrder() {
        metricReportEnabledSetting = true
        highResMetricsEnabledSetting = true
        finishReportSuccess = true
        sendReport = true
        sendHighRes = true
        reverseOrder = true
        runTest {
            val result = collector.finishAndCollectHeartbeatReport(timeout = 1.seconds)
            assertEquals(MetricReportWithHighResFile(report, highResFile), result)
            assertTrue(highResFile.exists())
        }
    }

    @Test
    fun successWithoutHighRes_enabled() {
        metricReportEnabledSetting = true
        highResMetricsEnabledSetting = true
        finishReportSuccess = true
        sendReport = true
        sendHighRes = false
        runTest {
            val result = collector.finishAndCollectHeartbeatReport(timeout = 1.seconds)
            assertEquals(MetricReportWithHighResFile(report, null), result)
            assertTrue(highResFile.exists())
        }
    }

    @Test
    fun successWithoutHighRes_disabled() {
        metricReportEnabledSetting = true
        highResMetricsEnabledSetting = false
        finishReportSuccess = true
        sendReport = true
        sendHighRes = false
        runTest {
            val result = collector.finishAndCollectHeartbeatReport(timeout = 1.seconds)
            assertEquals(MetricReportWithHighResFile(report, null), result)
            assertTrue(highResFile.exists())
        }
    }
}
