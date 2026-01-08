package de.orchestrator.gas_optimizer_orchestrator.model.etherscan

data class ContractCreationInfo(
    val contractAddress: String,
    val contractCreator: String,
    val txHash: String
)
