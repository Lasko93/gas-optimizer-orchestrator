package de.orchestrator.gas_optimizer_orchestrator.utils

object CompilerHelper {

    private const val MIN_VIA_IR_VERSION = "0.8.13"

    fun normalizeSolcVersion(raw: String): String {
        // Etherscan gives e.g. "v0.8.23+commit.f704f362" -> we want "0.8.23"
        return raw.trim()
            .removePrefix("v")
            .substringBefore("+")
            .substringBefore("-")
    }

    /**
     * Compares two semantic version strings.
     * Returns negative if v1 < v2, zero if v1 == v2, positive if v1 > v2.
     */
    fun compareSolcVersions(v1: String, v2: String): Int {
        val parts1 = v1.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val parts2 = v2.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }
        return 0
    }

    /**
     * Checks if the given solc version supports the --via-ir flag.
     * --via-ir was introduced in Solidity 0.8.13.
     */
    fun supportsViaIr(solcVersion: String): Boolean {
        return compareSolcVersions(solcVersion, MIN_VIA_IR_VERSION) >= 0
    }

    /**
     * Result of resolving via-ir compilation settings.
     */
    data class ViaIrConfig(
        val useViaIr: Boolean,
        val flag: String,
        val filePrefix: String
    )

    /**
     * Resolves via-ir compilation settings based on the requested flag and solc version.
     * If via-ir is requested but not supported, logs a warning and falls back to normal compilation.
     */
    fun resolveViaIrConfig(viaIrRequested: Boolean, solcVersion: String): ViaIrConfig {
        val supportsViaIr = supportsViaIr(solcVersion)
        val useViaIr = viaIrRequested && supportsViaIr

        if (viaIrRequested && !supportsViaIr) {
            println("Warning: --via-ir requested but solc $solcVersion does not support it (requires >= $MIN_VIA_IR_VERSION). Falling back to normal compilation.")
        }

        return ViaIrConfig(
            useViaIr = useViaIr,
            flag = if (useViaIr) "--via-ir" else "",
            filePrefix = if (useViaIr) "viair_runs" else "runs"
        )
    }

    /**
     * Builds remapping arguments string from a list of remappings.
     */
    fun buildRemapArgs(remappings: List<String>): String {
        return remappings.joinToString(" ")
    }

    /**
     * Builds optimizer runs tokens string from a list of run counts.
     */
    fun buildRunsTokens(runsList: List<Int>): String {
        return runsList.joinToString(" ") { it.toString() }
    }

    /**
     * Generates the solc-select use script to switch compiler versions.
     */
    fun solcSelectScript(solcVersion: String): String {
        val v = normalizeSolcVersion(solcVersion)
        return """solc-select use "$v" --always-install && chmod +x /home/ethsec/.solc-select/artifacts/solc-$v/solc-$v 2>/dev/null; solc --version"""
    }

    /**
     * Generates the bash script for optimized compilation with multiple optimizer runs.
     */
    fun optimizedCompilationScript(
        solFileName: String,
        remappings: List<String>,
        runsList: List<Int>,
        outDirName: String,
        viaIrConfig: ViaIrConfig
    ): String {
        val remapArgs = buildRemapArgs(remappings)
        val runsTokens = buildRunsTokens(runsList)

        return """
        set -euo pipefail
        mkdir -p "/share/$outDirName"
        cd /share
    
        for r in $runsTokens; do
          out_file="/share/$outDirName/${viaIrConfig.filePrefix}${'$'}r.json"
            
          solc $remapArgs \
            "$solFileName" \
            --combined-json abi,ast,bin,bin-runtime,srcmap,srcmap-runtime,userdoc,devdoc,hashes \
            --allow-paths .,/share \
            ${viaIrConfig.flag} \
            --optimize \
            --optimize-runs "${'$'}r" \
            > "${'$'}out_file"
          
          test -f "${'$'}out_file"
        done
        """.trimIndent()
    }

    /**
     * Generates the bash script for non-optimized baseline compilation.
     */
    fun baselineCompilationScript(
        solFileName: String,
        remappings: List<String>,
        outDirName: String,
        outFileName: String
    ): String {
        val remapArgs = buildRemapArgs(remappings)

        return """
        set -euo pipefail
        mkdir -p "/share/$outDirName"
        cd /share

        solc $remapArgs \
          "$solFileName" \
          --combined-json abi,ast,bin,bin-runtime,srcmap,srcmap-runtime,userdoc,devdoc,hashes \
          --allow-paths .,/share \
          > "/share/$outDirName/$outFileName"
        """.trimIndent()
    }
}