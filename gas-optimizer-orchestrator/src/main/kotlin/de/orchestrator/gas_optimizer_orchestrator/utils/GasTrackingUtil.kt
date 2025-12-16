package de.orchestrator.gas_optimizer_orchestrator.utils

import de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome
import de.orchestrator.gas_optimizer_orchestrator.utils.ReceiptUtil.isSuccess

object GasTrackingUtil {

    fun mapOutcomeToFunctionGasUsed(
        functionName: String,
        functionSignature: String,
        outcome: ReplayOutcome
    ): FunctionGasUsed {
        val receipt = outcome.receipt
        return if (receipt != null) {
            val ok = isSuccess(receipt)
            FunctionGasUsed(
                functionName = functionName,
                functionSignature = functionSignature,
                gasUsed = receipt.gasUsed?.toLong() ?: 0L,
                succeeded = ok,
                txHash = receipt.transactionHash,
                revertReason = if (ok) null else "status=${receipt.status}"
            )
        } else {
            FunctionGasUsed(
                functionName = functionName,
                functionSignature = functionSignature,
                gasUsed = 0L,
                succeeded = false,
                txHash = null,
                revertReason = outcome.errorMessage
            )
        }
    }
}
