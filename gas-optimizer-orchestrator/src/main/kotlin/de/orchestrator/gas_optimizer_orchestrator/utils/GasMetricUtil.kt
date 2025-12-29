package de.orchestrator.gas_optimizer_orchestrator.utils

import java.math.BigDecimal
import java.math.RoundingMode

object GasMetricsUtil {

    /**
     * Calculate percentage change from baseline to optimized.
     * Negative = savings, Positive = increase
     */
    fun percentageChange(baseline: Long, optimized: Long): Double {
        if (baseline == 0L) return 0.0
        return ((optimized - baseline).toDouble() / baseline) * 100.0
    }

    /**
     * Format percentage with sign and specified decimal places
     */
    fun formatPercent(percent: Double, decimals: Int = 2): String {
        val rounded = BigDecimal(percent).setScale(decimals, RoundingMode.HALF_UP)
        return if (percent >= 0) "+$rounded%" else "$rounded%"
    }

}