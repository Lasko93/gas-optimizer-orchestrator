package de.orchestrator.gas_optimizer_orchestrator.utils

object CompilerHelper {
    fun normalizeSolcVersion(raw: String): String {
        // Etherscan gives e.g. "v0.8.23+commit.f704f362" -> we want "0.8.23"
        return raw.trim()
            .removePrefix("v")
            .substringBefore("+")
            .substringBefore("-")
    }
    fun evmFlag(evmVersion: String?): String {
        val v = evmVersion?.trim()
        if (v.isNullOrEmpty()) return ""
        if (v.equals("default", ignoreCase = true)) return ""
        return "--evm-version $v"
    }

    fun optimizerFlags(optimizationUsed: Boolean, runs: Int): String {
        return if (optimizationUsed) "--optimize --optimize-runs $runs" else ""
    }
}