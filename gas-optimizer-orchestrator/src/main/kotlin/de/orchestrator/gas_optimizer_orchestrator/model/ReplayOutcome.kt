package de.orchestrator.gas_optimizer_orchestrator.model

import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigInteger

sealed class ReplayOutcome {

    data class Success(
        val receipt: TransactionReceipt,
        val gasUsed: BigInteger
    ) : ReplayOutcome()

    data class Reverted(
        val receipt: TransactionReceipt,
        val gasUsed: BigInteger,
        val revertReason: String?
    ) : ReplayOutcome()

    data class Failed(
        val errorMessage: String,
        val callRevertReason: String? = null
    ) : ReplayOutcome()

    fun isSuccess(): Boolean = this is Success

    fun gasUsedOrNull(): BigInteger? = when (this) {
        is Success -> gasUsed
        is Reverted -> gasUsed
        is Failed -> null
    }
}