package de.orchestrator.gas_optimizer_orchestrator.model.compilation

import java.io.File

data class CompiledContract(
    val artifactFile: File,
    val creationBytecode: String,
    val runtimeBytecode: String
)
