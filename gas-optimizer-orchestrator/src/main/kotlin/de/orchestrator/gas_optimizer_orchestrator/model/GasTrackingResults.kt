package de.orchestrator.gas_optimizer_orchestrator.model

data class GasTrackingResults(
    val contractName: String,
    val contractAddress: String,
    val compilerInfo: CompilerInfo,
    val gasProfile: GasProfile,
    val runContext: RunContext? = null
)

data class CompilerInfo(
    val solcVersion: String
)

data class GasProfile(
    val deploymentGasUsed: Long,
    val functionCalls: List<FunctionGasUsed>
)

data class FunctionGasUsed(
    val functionName: String,
    val functionSignature: String, // e.g. "transfer(address,uint256)"
    val gasUsed: Long,
    val succeeded: Boolean,
    val txHash: String? = null,
    val revertReason: String? = null
)

data class RunContext(
    val chainId: Long? = null,
    val forkBlockNumber: Long? = null,
    val rpcUrl: String? = null
)
