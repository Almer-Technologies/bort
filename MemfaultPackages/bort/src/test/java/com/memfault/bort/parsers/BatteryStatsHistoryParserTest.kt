package com.memfault.bort.parsers

import com.memfault.bort.metrics.HighResTelemetry.DataType.DoubleType
import com.memfault.bort.metrics.HighResTelemetry.DataType.StringType
import com.memfault.bort.metrics.HighResTelemetry.Datum
import com.memfault.bort.metrics.HighResTelemetry.MetricType.Event
import com.memfault.bort.metrics.HighResTelemetry.MetricType.Gauge
import com.memfault.bort.metrics.HighResTelemetry.MetricType.Property
import com.memfault.bort.metrics.HighResTelemetry.Rollup
import com.memfault.bort.metrics.HighResTelemetry.RollupMetadata
import com.memfault.bort.parsers.BatteryStatsConstants.ALARM
import com.memfault.bort.parsers.BatteryStatsConstants.AUDIO
import com.memfault.bort.parsers.BatteryStatsConstants.BATTERY_HEALTH
import com.memfault.bort.parsers.BatteryStatsConstants.BATTERY_LEVEL
import com.memfault.bort.parsers.BatteryStatsConstants.BATTERY_TEMP
import com.memfault.bort.parsers.BatteryStatsConstants.BLUETOOTH_LE_SCANNING
import com.memfault.bort.parsers.BatteryStatsConstants.BOOL_VALUE_FALSE
import com.memfault.bort.parsers.BatteryStatsConstants.BOOL_VALUE_TRUE
import com.memfault.bort.parsers.BatteryStatsConstants.CPU_RUNNING
import com.memfault.bort.parsers.BatteryStatsConstants.DOZE
import com.memfault.bort.parsers.BatteryStatsConstants.FOREGROUND
import com.memfault.bort.parsers.BatteryStatsConstants.GPS_ON
import com.memfault.bort.parsers.BatteryStatsConstants.LONGWAKE
import com.memfault.bort.parsers.BatteryStatsConstants.PHONE_RADIO
import com.memfault.bort.parsers.BatteryStatsConstants.PHONE_SCANNING
import com.memfault.bort.parsers.BatteryStatsConstants.SCREEN_BRIGHTNESS
import com.memfault.bort.parsers.BatteryStatsConstants.SCREEN_ON
import com.memfault.bort.parsers.BatteryStatsConstants.START
import com.memfault.bort.parsers.BatteryStatsConstants.TOP_APP
import com.memfault.bort.parsers.BatteryStatsConstants.WIFI_ON
import com.memfault.bort.parsers.BatteryStatsConstants.WIFI_RADIO
import com.memfault.bort.parsers.BatteryStatsConstants.WIFI_RUNNING
import com.memfault.bort.parsers.BatteryStatsConstants.WIFI_SCAN
import com.memfault.bort.parsers.BatteryStatsConstants.WIFI_SIGNAL_STRENGTH
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryStatsHistoryParserTest {
    private fun createFile(content: String): File {
        val file = File.createTempFile("batterystats", ".txt")
        file.writeText(content)
        return file
    }

    private val BATTERYSTATS_FILE = """
        9,hsp,1,0,"Abort:Pending Wakeup Sources: 200f000.qcom,spmi:qcom,pm660@0:qpnp,fg battery qcom-step-chg "
        9,hsp,70,10103,"com.android.launcher3"
        9,hsp,71,10104,"com.memfault.bort"
        9,h,123:TIME:1000000
        9,h,1:START
        9,h,0,+r,wr=1,Bl=100,+S,Sb=0,+W,+Wr,+Ws,+Ww,Wss=0,-g,+bles,+Pr,+Psc,+a,Bh=g,di=light,Bt=213,+Efg=71,+Elw=70
        9,h,200000,-r,-S,Sb=3,-W,-Wr,-Ws,-Ww,Wss=2,+g,-bles,-Pr,-Psc,-a,Bh=f,di=full,Bt=263,+Etp=70,+Efg=70,+Eal=70
        9,h,3,-Efg=71,-Etp=71,wr=,Bl=x,+W
        9,h,0:SHUTDOWN
        9,h,123:RESET:TIME:2000000
        9,h,800000,Bl=90,Bt=250,-Efg=70,-Etp=70,-Elw=70
    """.trimIndent()

    private val EXPECTED_HRT = listOf(
        Rollup(
            RollupMetadata(stringKey = CPU_RUNNING, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(stringKey = BATTERY_LEVEL, metricType = Gauge, dataType = DoubleType, internal = false),
            listOf(Datum(t = 1000001, JsonPrimitive(100)), Datum(t = 2800000, JsonPrimitive(90))),
        ),
        Rollup(
            RollupMetadata(stringKey = BATTERY_TEMP, metricType = Gauge, dataType = DoubleType, internal = false),
            listOf(
                Datum(t = 1000001, JsonPrimitive(213)),
                Datum(t = 1200001, JsonPrimitive(263)),
                Datum(t = 2800000, JsonPrimitive(250))
            ),
        ),
        Rollup(
            RollupMetadata(
                stringKey = BATTERY_HEALTH,
                metricType = Property,
                dataType = StringType,
                internal = false
            ),
            listOf(Datum(t = 1000001, JsonPrimitive("Good")), Datum(t = 1200001, JsonPrimitive("Failure"))),
        ),
        Rollup(
            RollupMetadata(stringKey = AUDIO, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(stringKey = GPS_ON, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_FALSE), Datum(t = 1200001, BOOL_VALUE_TRUE)),
        ),
        Rollup(
            RollupMetadata(stringKey = SCREEN_ON, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(
                stringKey = SCREEN_BRIGHTNESS,
                metricType = Property,
                dataType = StringType,
                internal = false,
            ),
            listOf(Datum(t = 1000001, JsonPrimitive("Dark")), Datum(t = 1200001, JsonPrimitive("Light"))),
        ),
        Rollup(
            RollupMetadata(stringKey = WIFI_ON, metricType = Property, dataType = StringType, internal = false),
            listOf(
                Datum(t = 1000001, BOOL_VALUE_TRUE),
                Datum(t = 1200001, BOOL_VALUE_FALSE),
                Datum(t = 1200004, BOOL_VALUE_TRUE),
            ),
        ),
        Rollup(
            RollupMetadata(stringKey = WIFI_SCAN, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(stringKey = WIFI_RADIO, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(stringKey = WIFI_RUNNING, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(
                stringKey = WIFI_SIGNAL_STRENGTH,
                metricType = Property,
                dataType = StringType,
                internal = false
            ),
            listOf(Datum(t = 1000001, JsonPrimitive("VeryPoor")), Datum(t = 1200001, JsonPrimitive("Moderate"))),
        ),
        Rollup(
            RollupMetadata(stringKey = DOZE, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, JsonPrimitive("Light")), Datum(t = 1200001, JsonPrimitive("Full"))),
        ),
        Rollup(
            RollupMetadata(
                stringKey = BLUETOOTH_LE_SCANNING,
                metricType = Property,
                dataType = StringType,
                internal = false
            ),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(stringKey = PHONE_RADIO, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        Rollup(
            RollupMetadata(
                stringKey = PHONE_SCANNING,
                metricType = Property,
                dataType = StringType,
                internal = false
            ),
            listOf(Datum(t = 1000001, BOOL_VALUE_TRUE), Datum(t = 1200001, BOOL_VALUE_FALSE)),
        ),
        // -Etp=71 ignored, because 71 was not top app
        Rollup(
            RollupMetadata(stringKey = TOP_APP, metricType = Property, dataType = StringType, internal = false),
            listOf(
                Datum(t = 1200001, JsonPrimitive("com.android.launcher3")),
                Datum(t = 2800000, JsonPrimitive(null as String?))
            ),
        ),
        // -Efg=71 ignored, because 71 was not foreground
        Rollup(
            RollupMetadata(stringKey = FOREGROUND, metricType = Property, dataType = StringType, internal = false),
            listOf(
                Datum(t = 1000001, JsonPrimitive("com.memfault.bort")),
                Datum(t = 1200001, JsonPrimitive("com.android.launcher3")),
                Datum(t = 2800000, JsonPrimitive(null as String?))
            ),
        ),
        Rollup(
            RollupMetadata(stringKey = LONGWAKE, metricType = Property, dataType = StringType, internal = false),
            listOf(
                Datum(t = 1000001, JsonPrimitive("com.android.launcher3")),
                Datum(t = 2800000, JsonPrimitive(null as String?))
            ),
        ),
        Rollup(
            RollupMetadata(stringKey = ALARM, metricType = Property, dataType = StringType, internal = false),
            listOf(Datum(t = 1200001, JsonPrimitive("com.android.launcher3"))),
        ),
        Rollup(
            RollupMetadata(stringKey = START, metricType = Event, dataType = StringType, internal = false),
            listOf(Datum(t = 1000001, JsonPrimitive("Start")), Datum(t = 1200004, JsonPrimitive("Shutdown"))),
        ),
    )

    @Test
    fun testParser() {
        val parser = BatteryStatsHistoryParser(createFile(BATTERYSTATS_FILE))
        runTest {
            val result = parser.parseToCustomMetrics()
            assertEquals(EXPECTED_HRT, result.batteryStatsHrt)
        }
    }

    // Copied from test_batterystats_history_aggregator.py, so that we match the aggregate output of the backend.
    private val AGGREGATE_FILE = """
        9,hsp,1,0,"Abort:Pending Wakeup Sources: 200f000.qcom,spmi:qcom,pm660@0:qpnp,fg battery qcom-step-chg "
        9,h,123:TIME:1000000
        9,h,0,+r,wr=1,Bl=100,+S,Sb=0,+W,+Wr,+Ws,+Ww,Wss=0,+g,+bles,+Pr,+Psc,+a,Bh=g,di=light,Bt=213
        9,h,200000,-r,-S,Sb=3,-W,-Wr,-Ws,-Ww,Wss=2,-g,-bles,-Pr,-Psc,-a,Bh=f,di=full,Bt=263
        9,h,800000,Bl=90,Bt=250
    """.trimIndent()

    private val EXPECTED_AGGREGATES = mapOf(
        "audio_on_ratio" to JsonPrimitive(0.2),
//        "battery_charge_rate_pct_per_hour_avg" to JsonPrimitive(null as Double?), // Absent = correct
        "battery_discharge_rate_pct_per_hour_avg" to JsonPrimitive(-36.0),
        "battery_health_not_good_ratio" to JsonPrimitive(0.8),
        "battery_level_pct_avg" to JsonPrimitive(95.0),
        "cpu_resume_count_per_hour" to JsonPrimitive(3.6),
        "cpu_suspend_count_per_hour" to JsonPrimitive(3.6),
        "cpu_running_ratio" to JsonPrimitive(0.2),
        "bluetooth_scan_ratio" to JsonPrimitive(0.2),
        "doze_full_ratio" to JsonPrimitive(0.8),
        "doze_ratio" to JsonPrimitive(1.0),
        "gps_on_ratio" to JsonPrimitive(0.2),
        "max_battery_temp" to JsonPrimitive(263.0),
        "phone_radio_active_ratio" to JsonPrimitive(0.2),
        "phone_scanning_ratio" to JsonPrimitive(0.2),
        "phone_signal_strength_none_ratio" to JsonPrimitive(0.0),
        "phone_signal_strength_poor_ratio" to JsonPrimitive(0.0),
        "screen_brightness_light_or_bright_ratio" to JsonPrimitive(0.8),
        "screen_on_ratio" to JsonPrimitive(0.2),
        "wifi_on_ratio" to JsonPrimitive(0.2),
        "wifi_radio_active_ratio" to JsonPrimitive(0.2),
        "wifi_running_ratio" to JsonPrimitive(0.2),
        "wifi_scan_ratio" to JsonPrimitive(0.2),
        "wifi_signal_strength_poor_or_very_poor_ratio" to JsonPrimitive(0.2),
    )

    @Test
    fun testAggregatesMatchBackend() {
        val parser = BatteryStatsHistoryParser(createFile(AGGREGATE_FILE))
        runTest {
            val result = parser.parseToCustomMetrics()
            assertEquals(EXPECTED_AGGREGATES, result.aggregatedMetrics)
        }
    }
}
