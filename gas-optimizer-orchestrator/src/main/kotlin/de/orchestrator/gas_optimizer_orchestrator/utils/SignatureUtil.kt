package de.orchestrator.gas_optimizer_orchestrator.utils


object SignatureUtil {
    fun signature(functionName: String, abiTypes: List<String>): String =
        "$functionName(${abiTypes.joinToString(",")})"
}
