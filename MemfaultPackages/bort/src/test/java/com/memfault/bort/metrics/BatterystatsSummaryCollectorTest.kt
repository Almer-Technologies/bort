package com.memfault.bort.metrics

import com.memfault.bort.metrics.HighResTelemetry.Rollup
import com.memfault.bort.metrics.HighResTelemetry.RollupMetadata
import com.memfault.bort.parsers.BatteryStatsSummaryParser
import com.memfault.bort.parsers.BatteryStatsSummaryParser.BatteryState
import com.memfault.bort.parsers.BatteryStatsSummaryParser.BatteryStatsSummary
import com.memfault.bort.parsers.BatteryStatsSummaryParser.DischargeData
import com.memfault.bort.parsers.BatteryStatsSummaryParser.PowerUseItemData
import com.memfault.bort.settings.BatteryStatsSettings
import com.memfault.bort.test.util.TestTemporaryFileFactory
import io.mockk.every
import io.mockk.mockk
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test
import org.junit.jupiter.api.Assertions.assertEquals

internal class BatterystatsSummaryCollectorTest {
    private val timeMs: Long = 123456789
    private val parser: BatteryStatsSummaryParser = mockk {
        every { parse(any()) } answers { batteryStatsSummary }
    }
    private val settings = object : BatteryStatsSettings {
        override val dataSourceEnabled: Boolean = true
        override val commandTimeout: Duration = 10.seconds
        override val useHighResTelemetry: Boolean = true
        override val collectSummary: Boolean = true
    }
    private var lastSummary: BatteryStatsSummary? = null
    private val provider = object : BatteryStatsSummaryProvider {
        override fun get(): BatteryStatsSummary? {
            return lastSummary
        }

        override fun set(summary: BatteryStatsSummary) {
            lastSummary = summary
        }
    }
    private var batteryStatsSummary: BatteryStatsSummary? = null
    private val runBatteryStats: RunBatteryStats = mockk(relaxed = true)
    private val summaryCollector = BatterystatsSummaryCollector(
        temporaryFileFactory = TestTemporaryFileFactory,
        runBatteryStats = runBatteryStats,
        settings = settings,
        batteryStatsSummaryParser = parser,
        batteryStatsSummaryProvider = provider,
    )

    private val CHECKIN_1_DISCHARGING = BatteryStatsSummary(
        batteryState = BatteryState(
            batteryRealtimeMs = 27768496,
            startClockTimeMs = 1681397881665,
            estimatedBatteryCapacity = 3777,
            screenOffRealtimeMs = 13884248,
        ),
        dischargeData = DischargeData(totalMaH = 1128, totalMaHScreenOff = 611),
        powerUseItemData = setOf(
            PowerUseItemData(name = "android", totalPowerMaH = 217.05899999999997),
            PowerUseItemData(name = "unknown", totalPowerMaH = 33.0),
            PowerUseItemData(name = "com.google.android.apps.maps", totalPowerMaH = 114.0),
        ),
        timestampMs = timeMs,
    )

    private val CHECKIN_2_DISCHARGING = BatteryStatsSummary(
        batteryState = BatteryState(
            batteryRealtimeMs = 30145036, // 2376540 diff from 1st
            startClockTimeMs = 1681397881665,
            estimatedBatteryCapacity = 3777,
            screenOffRealtimeMs = 14478383, // 594135 diff from 1st
        ),
        dischargeData = DischargeData(totalMaH = 1233, totalMaHScreenOff = 686),
        powerUseItemData = setOf(
            PowerUseItemData(name = "screen", totalPowerMaH = 202.0),
            PowerUseItemData(name = "android", totalPowerMaH = 247.08),
            PowerUseItemData(name = "com.google.android.youtube", totalPowerMaH = 58.5),
        ),
        timestampMs = timeMs,
    )

    @Test
    fun initialRunNotCompared() {
        runTest {
            lastSummary = null
            batteryStatsSummary = CHECKIN_1_DISCHARGING
            val result = summaryCollector.collectSummaryCheckin()
            // period = 27768496
            // 27768496/3600000=7.713471111111111
            // screen on = 0.5 screen off = 0.5
            // capacity = 3777
            assertEquals(
                BatteryStatsResult(
                    batteryStatsFileToUpload = null,
                    batteryStatsHrt = setOf(
                        // 611/3777*100 / 7.713471111111111 / 0.5 = 4.194443645079644
                        createRollup(name = "screen_off_battery_drain_%/hour", value = 4.19),
                        // (1128-611=517)/3777*100 / 7.713471111111111 / 0.5 = 3.5491446227597
                        createRollup(name = "screen_on_battery_drain_%/hour", value = 3.55),
                        // (217.059 / 3777 * 100) / 7.713471111111111 = 0.745042343009282
                        createRollup(name = "battery_use_%/hour_android", value = 0.75),
                        // (114 / 3777 * 100) / 7.713471111111111 = 0.391298343321669
                        createRollup(name = "battery_use_%/hour_com.google.android.apps.maps", value = 0.39),
                        // (33 / 3777 * 100) / 7.713471111111111 = 0.113270573066799
                        createRollup(name = "battery_use_%/hour_unknown", value = 0.11),
                        createRollup(name = "estimated_battery_capacity_mah", value = 3777),
                    ),
                    aggregatedMetrics = mapOf(
                        "screen_off_battery_drain_%/hour" to JsonPrimitive(4.19),
                        "screen_on_battery_drain_%/hour" to JsonPrimitive(3.55),
                        "estimated_battery_capacity_mah" to JsonPrimitive(3777),
                    ),
                ),
                result
            )
        }
    }

    @Test
    fun subsequentRunComparedToPrevious() = runTest {
        lastSummary = CHECKIN_1_DISCHARGING
        batteryStatsSummary = CHECKIN_2_DISCHARGING
        val result = summaryCollector.collectSummaryCheckin() // .filterComponents()
        // period = 30145036 - 27768496 = 2376540 (~40 minutes)
        // 2376540/3600000=0.66015
        // screen on = 0.75 screen off = 0.25
        // capacity = 3777
        assertEquals(
            BatteryStatsResult(
                batteryStatsFileToUpload = null,
                batteryStatsHrt = setOf(
                    // (686-611=75)/3777*100 / 0.66015 / 0.25 = 12.031828759162916
                    createRollup(name = "screen_off_battery_drain_%/hour", value = 12.03),
                    // (1233-1128-(686-611)=30)/3777*100 / 0.66015 / 0.75 = 1.604243834555055
                    createRollup(name = "screen_on_battery_drain_%/hour", value = 1.60),
                    // ((58.5 - 0) / 3777 * 100) / 0.66015 = 2.346206608036768
                    createRollup(name = "battery_use_%/hour_com.google.android.youtube", value = 2.35),
                    // ((247.08 - 217.05899999999997=30.021) / 3777 * 100) / 0.66015 = 1.204025103929433
                    createRollup(name = "battery_use_%/hour_android", value = 1.20),
                    // ((202 - 0) / 3777 * 100) / 0.66015 = 8.101431364503029
                    createRollup(name = "battery_use_%/hour_screen", value = 8.10),
                    createRollup(name = "estimated_battery_capacity_mah", value = 3777),
                ),
                aggregatedMetrics = mapOf(
                    "screen_off_battery_drain_%/hour" to JsonPrimitive(12.03),
                    "screen_on_battery_drain_%/hour" to JsonPrimitive(1.60),
                    "estimated_battery_capacity_mah" to JsonPrimitive(3777),
                ),
            ),
            result
        )
    }

    private val CHECKIN_NO_BATTERY = BatteryStatsSummary(
        batteryState = BatteryState(
            batteryRealtimeMs = 0,
            startClockTimeMs = 1683752599879,
            estimatedBatteryCapacity = 0,
            screenOffRealtimeMs = 0,
        ),
        dischargeData = DischargeData(totalMaH = 0, totalMaHScreenOff = 0),
        powerUseItemData = setOf(),
        timestampMs = timeMs,
    )

    @Test
    fun noBattery() = runTest {
        lastSummary = null
        batteryStatsSummary = CHECKIN_NO_BATTERY
        val result = summaryCollector.collectSummaryCheckin()
        assertEquals(BatteryStatsResult.EMPTY, result)
    }

    @Test
    fun noBatterySubsequentRun() = runTest {
        lastSummary = CHECKIN_NO_BATTERY
        batteryStatsSummary = CHECKIN_NO_BATTERY
        val result = summaryCollector.collectSummaryCheckin()
        assertEquals(BatteryStatsResult.EMPTY, result)
    }

    private val CHECKIN_CHARGING = BatteryStatsSummary(
        batteryState = BatteryState(
            batteryRealtimeMs = 3600000,
            startClockTimeMs = 1683752599879,
            estimatedBatteryCapacity = 1000,
            screenOffRealtimeMs = 0,
        ),
        dischargeData = DischargeData(totalMaH = 0, totalMaHScreenOff = 0),
        powerUseItemData = setOf(),
        timestampMs = timeMs,
    )

    @Test
    fun charging() = runTest {
        lastSummary = CHECKIN_CHARGING
        batteryStatsSummary = CHECKIN_CHARGING
        val result = summaryCollector.collectSummaryCheckin()
        assertEquals(
            BatteryStatsResult(
                batteryStatsFileToUpload = null,
                batteryStatsHrt = setOf(
                    createRollup(name = "estimated_battery_capacity_mah", value = 1000),
                ),
                aggregatedMetrics = mapOf(
                    "estimated_battery_capacity_mah" to JsonPrimitive(1000),
                ),
            ),
            result,
        )
    }

    private val CHECKIN_DISCHARGING = BatteryStatsSummary(
        batteryState = BatteryState(
            batteryRealtimeMs = 3600001,
            startClockTimeMs = CHECKIN_CHARGING.batteryState.startClockTimeMs + 1000,
            estimatedBatteryCapacity = 1000,
            screenOffRealtimeMs = 0,
        ),
        dischargeData = DischargeData(totalMaH = 100, totalMaHScreenOff = 60),
        powerUseItemData = setOf(),
        timestampMs = timeMs,
    )

    @Test
    fun newChargeCycle() = runTest {
        lastSummary = CHECKIN_CHARGING
        batteryStatsSummary = CHECKIN_DISCHARGING
        val result = summaryCollector.collectSummaryCheckin()
        assertEquals(
            BatteryStatsResult(
                batteryStatsFileToUpload = null,
                batteryStatsHrt = setOf(
                    createRollup(name = "screen_on_battery_drain_%/hour", value = 4.0),
                    createRollup(name = "estimated_battery_capacity_mah", value = 1000),
                ),
                aggregatedMetrics = mapOf(
                    "screen_on_battery_drain_%/hour" to JsonPrimitive(4.0),
                    "estimated_battery_capacity_mah" to JsonPrimitive(1000),
                ),
            ),
            result
        )
    }

    private val CHECKIN_LOW_USAGE = BatteryStatsSummary(
        batteryState = BatteryState(
            batteryRealtimeMs = 3600000,
            startClockTimeMs = 0,
            estimatedBatteryCapacity = 1000,
            screenOffRealtimeMs = 1800000,
        ),
        dischargeData = DischargeData(totalMaH = 100, totalMaHScreenOff = 50),
        powerUseItemData = setOf(
            PowerUseItemData(name = "android", totalPowerMaH = 0.04),
        ),
        timestampMs = timeMs,
    )

    @Test
    fun lowComponentUsageNotReported() {
        // "android" usage is too low (would report 0%, so is not included in output).
        runTest {
            lastSummary = null
            batteryStatsSummary = CHECKIN_LOW_USAGE
            val result = summaryCollector.collectSummaryCheckin()
            assertEquals(
                BatteryStatsResult(
                    batteryStatsFileToUpload = null,
                    batteryStatsHrt = setOf(
                        createRollup(name = "screen_off_battery_drain_%/hour", value = 10.0),
                        createRollup(name = "screen_on_battery_drain_%/hour", value = 10.0),
                        createRollup(name = "estimated_battery_capacity_mah", value = 1000),
                    ),
                    aggregatedMetrics = mapOf(
                        "screen_off_battery_drain_%/hour" to JsonPrimitive(10.0),
                        "screen_on_battery_drain_%/hour" to JsonPrimitive(10.0),
                        "estimated_battery_capacity_mah" to JsonPrimitive(1000),
                    ),
                ),
                result
            )
        }
    }

    private fun createRollup(name: String, value: Number) = Rollup(
        metadata = RollupMetadata(
            stringKey = name,
            metricType = HighResTelemetry.MetricType.Gauge,
            dataType = HighResTelemetry.DataType.DoubleType,
            internal = false,
        ),
        data = listOf(
            HighResTelemetry.Datum(
                t = timeMs,
                value = JsonPrimitive(value),
            )
        ),
    )
}
