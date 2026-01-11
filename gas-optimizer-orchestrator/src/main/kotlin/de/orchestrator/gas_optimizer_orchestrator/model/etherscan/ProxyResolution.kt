package de.orchestrator.gas_optimizer_orchestrator.model.etherscan

data class ProxyResolution(
    val isProxy: Boolean,
    val proxyAddress: String?,
    val proxySourceMeta: ContractSourceCodeResult?,
    val implementationAddress: String,
    val implementationSourceMeta: ContractSourceCodeResult
)