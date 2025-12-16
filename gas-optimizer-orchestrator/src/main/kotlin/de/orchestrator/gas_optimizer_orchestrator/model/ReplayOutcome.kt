package de.orchestrator.gas_optimizer_orchestrator.model

import org.web3j.protocol.core.methods.response.TransactionReceipt

data class ReplayOutcome(
    val receipt: TransactionReceipt?,
    val errorMessage: String?
)