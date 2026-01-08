package de.orchestrator.gas_optimizer_orchestrator.service.slither

internal object SlitherOutputCleaner {

    private val NOISE_PATTERNS = listOf(
        "level=warning",
        "Switched global version",
        "solc, the solidity compiler",
        "Version:"
    )

    fun clean(output: String): String =
        output.lines()
            .filterNot { line -> isNoise(line) }
            .joinToString("\n")
            .trim()

    fun containsJson(output: String): Boolean =
        output.isNotEmpty() && output.contains("{")

    private fun isNoise(line: String): Boolean =
        line.trim().isEmpty() || NOISE_PATTERNS.any { line.contains(it) }
}