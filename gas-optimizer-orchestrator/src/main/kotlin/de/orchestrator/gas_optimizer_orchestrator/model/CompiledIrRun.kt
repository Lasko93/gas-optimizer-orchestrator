package de.orchestrator.gas_optimizer_orchestrator.model

import java.io.File

data class CompiledIrRun(
    val optimizeRuns: Int,
    val combinedJsonFile: File,
    val creationBytecode: String,
    val runtimeBytecode: String,
    val solcVersion: String? = null
)