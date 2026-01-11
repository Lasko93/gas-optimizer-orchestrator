package de.orchestrator.gas_optimizer_orchestrator.utils

import de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome

/**
 * Utility for tracking and converting gas usage data.
 */
object GasTrackingUtil {

    /**
     * Converts a replay outcome to a FunctionGasUsed record.
     *
     * @param functionName The name of the function that was called
     * @param functionSignature The full function signature (e.g., "transfer(address,uint256)")
     * @param outcome The result of replaying the transaction
     * @return A FunctionGasUsed record capturing the gas usage and status
     */
    fun mapOutcomeToFunctionGasUsed(
        functionName: String,
        functionSignature: String,
        outcome: ReplayOutcome
    ): FunctionGasUsed = when (outcome) {
        is ReplayOutcome.Success -> successResult(functionName, functionSignature, outcome)
        is ReplayOutcome.Reverted -> revertedResult(functionName, functionSignature, outcome)
        is ReplayOutcome.Failed -> failedResult(functionName, functionSignature, outcome)
    }

    private fun successResult(
        functionName: String,
        functionSignature: String,
        outcome: ReplayOutcome.Success
    ) = FunctionGasUsed(
        functionName = functionName,
        functionSignature = functionSignature,
        gasUsed = outcome.gasUsed.toLong(),
        succeeded = true,
        txHash = outcome.receipt.transactionHash,
        revertReason = null
    )

    private fun revertedResult(
        functionName: String,
        functionSignature: String,
        outcome: ReplayOutcome.Reverted
    ) = FunctionGasUsed(
        functionName = functionName,
        functionSignature = functionSignature,
        gasUsed = outcome.gasUsed.toLong(),
        succeeded = false,
        txHash = outcome.receipt.transactionHash,
        revertReason = outcome.revertReason
    )

    private fun failedResult(
        functionName: String,
        functionSignature: String,
        outcome: ReplayOutcome.Failed
    ) = FunctionGasUsed(
        functionName = functionName,
        functionSignature = functionSignature,
        gasUsed = 0L,
        succeeded = false,
        txHash = null,
        revertReason = outcome.callRevertReason ?: outcome.errorMessage
    )
}