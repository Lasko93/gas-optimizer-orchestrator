package de.orchestrator.gas_optimizer_orchestrator.model.compilation

import java.io.File

data class CompiledIrRun(
    val optimizeRuns: Int,
    val combinedJsonFile: File,
    val creationBytecode: String,
    val runtimeBytecode: String,
    val solcVersion: String? = null,
    val creationBytecodeSize: Int = (creationBytecode.removePrefix("0x").length) / 2,
    val runtimeBytecodeSize: Int = (runtimeBytecode.removePrefix("0x").length) / 2
)