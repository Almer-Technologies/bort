package com.memfault.bort.metrics

import kotlin.time.Duration.Companion.hours
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Replicates the existing batterystats aggregations which were done in the backend, when the raw batterystats file was
 * uploaded.
 */
sealed class BatteryStatsAgg {
    abstract fun addValue(elapsedMs: Long, value: JsonPrimitive)

    abstract fun finish(elapsedMs: Long): List<Pair<String, JsonPrimitive>>

    class TimeByNominalAggregator(
        private val metricName: String,
        private val states: List<JsonPrimitive>,
    ) : BatteryStatsAgg() {
        private var prevVal: JsonPrimitive? = null
        private var prevElapsedMs: Long? = null
        private var timeInStateMsRunning: Long = 0

        override fun addValue(elapsedMs: Long, value: JsonPrimitive) {
            handleLastVal(elapsedMs)
            prevVal = value
            prevElapsedMs = elapsedMs
        }

        private fun handleLastVal(elapsedMs: Long) {
            prevVal?.let { prev ->
                val prevTime = prevElapsedMs ?: return@let
                val msSincePrev = elapsedMs - prevTime
                if (msSincePrev > 0 && prev in states) {
                    timeInStateMsRunning += msSincePrev
                }
            }
        }

        override fun finish(elapsedMs: Long): List<Pair<String, JsonPrimitive>> {
            if (elapsedMs <= 0) return emptyList()
            handleLastVal(elapsedMs)
            return listOf(Pair(metricName, JsonPrimitive(timeInStateMsRunning.toDouble() / elapsedMs.toDouble())))
        }
    }

    class CountByNominalAggregator(
        private val metricName: String,
        private val state: JsonPrimitive,
    ) : BatteryStatsAgg() {
        private var count = 0

        override fun addValue(elapsedMs: Long, value: JsonPrimitive) {
            if (value == state) count++
        }

        override fun finish(elapsedMs: Long): List<Pair<String, JsonPrimitive>> {
            return listOf(
                Pair(
                    metricName,
                    JsonPrimitive(count.toDouble().perHour(elapsedMs.toDouble()))
                )
            )
        }
    }

    class MaximumValueAggregator(
        private val metricName: String,
    ) : BatteryStatsAgg() {
        private var maxValue: Double? = null

        override fun addValue(elapsedMs: Long, value: JsonPrimitive) {
            value.doubleOrNull?.let {
                val prevMax = maxValue
                if (prevMax == null || it > prevMax) maxValue = it
            }
        }

        override fun finish(elapsedMs: Long): List<Pair<String, JsonPrimitive>> {
            return maxValue?.let {
                listOf(Pair(metricName, JsonPrimitive(it)))
            } ?: emptyList()
        }
    }

    class BatteryLevelAggregator() : BatteryStatsAgg() {
        private var prevVal: JsonPrimitive? = null
        private var prevElapsedMs: Long? = null
        private var runningValueLevel: Double? = null
        private var runningValueCharge: Double? = null
        private var runningValueDischarge: Double? = null

        override fun addValue(elapsedMs: Long, value: JsonPrimitive) {
            handleLastVal(elapsedMs, value)
            prevVal = value
            prevElapsedMs = elapsedMs
        }

        private fun handleLastVal(elapsedMs: Long, value: JsonPrimitive) {
            prevVal?.doubleOrNull?.let { prevDouble ->
                val prevTime = prevElapsedMs ?: return@let
                val msSincePrev = elapsedMs - prevTime
                if (msSincePrev > 0) {
                    value.doubleOrNull?.let { newDouble ->
                        val diff = newDouble - prevDouble
                        if (diff > 0) {
                            runningValueCharge = (runningValueCharge ?: 0.0) + (diff * msSincePrev)
                        } else if (diff < 0) {
                            runningValueDischarge = (runningValueDischarge ?: 0.0) + (diff * msSincePrev)
                        }
                        // Mean of time since last reading.
                        val avgPrevAndCurrent = (newDouble + prevDouble) / 2.0
                        runningValueLevel = (runningValueLevel ?: 0.0) + (avgPrevAndCurrent * msSincePrev)
                    }
                }
            }
        }

        override fun finish(elapsedMs: Long): List<Pair<String, JsonPrimitive>> {
            if (elapsedMs <= 0) return emptyList()
            prevVal?.let { handleLastVal(elapsedMs, it) }
            val results = mutableListOf<Pair<String, JsonPrimitive>>()
            runningValueLevel?.let {
                results.add(Pair("battery_level_pct_avg", JsonPrimitive(it / elapsedMs.toDouble())))
            }
            runningValueCharge?.let {
                results.add(
                    Pair(
                        "battery_charge_rate_pct_per_hour_avg",
                        JsonPrimitive(it.perHour(elapsedMs.toDouble()) / elapsedMs.toDouble())
                    )
                )
            }
            runningValueDischarge?.let {
                results.add(
                    Pair(
                        "battery_discharge_rate_pct_per_hour_avg",
                        JsonPrimitive(it.perHour(elapsedMs.toDouble()) / elapsedMs.toDouble())
                    )
                )
            }
            return results
        }
    }

    companion object {
        private fun Double.perHour(elapsedMs: Double): Double =
            this * (1.hours.inWholeMilliseconds.toDouble() / elapsedMs)
    }
}
