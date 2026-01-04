package de.orchestrator.gas_optimizer_orchestrator.utils

import de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome

object GasTrackingUtil {

    fun mapOutcomeToFunctionGasUsed(
        functionName: String,
        functionSignature: String,
        outcome: ReplayOutcome
    ): FunctionGasUsed {
        return when (outcome) {
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
    }
}