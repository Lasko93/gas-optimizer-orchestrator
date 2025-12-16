package de.orchestrator.gas_optimizer_orchestrator.model

import java.io.File

data class CompiledContract(
    val artifactFile: File,
    val creationBytecode: String,
    val deployBytecode: String
)
