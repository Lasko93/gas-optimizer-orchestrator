package de.orchestrator.gas_optimizer_orchestrator.utils.gas

import de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Utilities for gas analysis, metrics calculation, and result mapping.
 */
object GasAnalysisUtil {

    // ============================================================
    // Outcome Mapping
    // ============================================================

    /**
     * Converts a replay outcome to a FunctionGasUsed record.
     *
     * @param functionName The name of the function that was called
     * @param functionSignature The full function signature
     * @param outcome The result of replaying the transaction
     * @return A FunctionGasUsed record capturing the gas usage and status
     */
    fun mapOutcomeToResult(
        functionName: String,
        functionSignature: String,
        outcome: ReplayOutcome
    ): FunctionGasUsed = when (outcome) {
        is ReplayOutcome.Success -> FunctionGasUsed(
            functionName = functionName,
            functionSignature = functionSignature,
            gasUsed = outcome.gasUsed.toLong(),
            succeeded = true,
            txHash = outcome.receipt.transactionHash,
            revertReason = null
        )
        is ReplayOutcome.Reverted -> FunctionGasUsed(
            functionName = functionName,
            functionSignature = functionSignature,
            gasUsed = outcome.gasUsed.toLong(),
            succeeded = false,
            txHash = outcome.receipt.transactionHash,
            revertReason = outcome.revertReason
        )
        is ReplayOutcome.Failed -> FunctionGasUsed(
            functionName = functionName,
            functionSignature = functionSignature,
            gasUsed = 0L,
            succeeded = false,
            txHash = null,
            revertReason = outcome.callRevertReason ?: outcome.errorMessage
        )
    }

    // ============================================================
    // Metrics Calculation
    // ============================================================

    /**
     * Calculates percentage change from baseline to optimized.
     *
     * @return Negative value indicates savings, positive indicates increase
     */
    fun percentageChange(baseline: Long, optimized: Long): Double {
        if (baseline == 0L) return 0.0
        return ((optimized - baseline).toDouble() / baseline) * 100.0
    }

    // ============================================================
    // Formatting
    // ============================================================

    /**
     * Formats a percentage with sign and specified decimal places.
     */
    fun formatPercent(percent: Double, decimals: Int = 2): String {
        val rounded = BigDecimal(percent).setScale(decimals, RoundingMode.HALF_UP)
        return if (percent >= 0) "+$rounded%" else "$rounded%"
    }
}