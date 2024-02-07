package com.memfault.bort.metrics

import com.memfault.bort.metrics.CrashFreeHoursMetricLogger.Companion.CRASH_FREE_HOURS_METRIC_KEY
import com.memfault.bort.metrics.CrashFreeHoursMetricLogger.Companion.OPERATIONAL_HOURS_METRIC_KEY
import kotlinx.serialization.json.JsonPrimitive

/**
 * Handles renaming, filtering and post-processing metrics which are now generated by the Custom Metrics service, but
 * used to be created elsewhere (so that their naming/construction remains unchanged).
 */
object AggregateMetricFilter {
    /**
     * We need to:
     *  - Rename some batterystats metrics, so that they are named the same as backend-generated metrics.
     *  - Filter out some batterystats metrics, which aren't used (e.g. time in OFF state).
     *  - Post-process some metrics.
     */
    fun filterAndRenameMetrics(metrics: Map<String, JsonPrimitive>, internal: Boolean): Map<String, JsonPrimitive> {
        return metrics.mapNotNull { metric ->
            handleMetric(metric, internal)
        }.toMap()
    }

    private fun handleMetric(
        metric: Map.Entry<String, JsonPrimitive>,
        internal: Boolean,
    ): Pair<String, JsonPrimitive> {
        if (!internal) {
            // Special case: app versions. Drop .latest.
            if (metric.key.startsWith("version.")) {
                return metric.key.removeSuffix(".latest") to metric.value
            }
        }

        // Special case: sysprops (internal and external). Drop .latest.
        if (metric.key.startsWith("sysprop.")) {
            return metric.key.removeSuffix(".latest") to metric.value
        }

        // Special case: sync_.*success.sum and sync_.*failure.sum. Drop .sum.
        if (metric.key.endsWith("_successful.sum")) {
            return metric.key.removeSuffix(".sum") to metric.value
        }
        if (metric.key.endsWith("_failure.sum")) {
            return metric.key.removeSuffix(".sum") to metric.value
        }

        // Special case: crash-free hours. Drop .sum
        if (metric.key == "$OPERATIONAL_HOURS_METRIC_KEY.sum" || metric.key == "$CRASH_FREE_HOURS_METRIC_KEY.sum") {
            return metric.key.removeSuffix(".sum") to metric.value
        }

        // Sepcial case: bort_lite
        if (metric.key == "$BORT_LITE_METRIC_KEY.latest") {
            return BORT_LITE_METRIC_KEY to metric.value
        }

        // Internal metrics: drop the sum/latest suffixes.
        if (internal) {
            if (metric.key.endsWith(".sum")) return metric.key.removeSuffix(".sum") to metric.value
            if (metric.key.endsWith(".latest")) return metric.key.removeSuffix(".latest") to metric.value
        }

        // Untouched
        return metric.toPair()
    }
}
