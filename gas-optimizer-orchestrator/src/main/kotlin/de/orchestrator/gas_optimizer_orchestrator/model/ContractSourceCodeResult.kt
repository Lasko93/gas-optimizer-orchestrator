package de.orchestrator.gas_optimizer_orchestrator.model

data class ContractSourceCodeResult(
    val address: String,
    val contractName: String,
    val compilerVersion: String,
    val optimizationUsed: Boolean,
    val runs: Int,
    val evmVersion: String?,
    val sourceCode: String,          // normalized SourceCode (double-braces stripped)
    val isStandardJsonInput: Boolean, // true if SourceCode is Solidity standard-json-input
    val constructorArgumentsHex: String
)