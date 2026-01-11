package de.orchestrator.gas_optimizer_orchestrator.utils.compiler

/**
 * Builds bash scripts for Solidity compilation in Docker containers.
 */
object CompilerScriptBuilder {

    // ============================================================
    // Script Generation
    // ============================================================

    /**
     * Generates script to select a specific solc version.
     */
    fun solcSelectScript(solcVersion: String): String {
        val version = SolcVersionUtil.normalize(solcVersion)
        return """
            solc-select use "$version" --always-install && \
            chmod +x /home/ethsec/.solc-select/artifacts/solc-$version/solc-$version 2>/dev/null; \
            solc --version
        """.trimIndent()
    }

    /**
     * Generates script for baseline compilation (no optimization).
     *
     * @param viaIr If true, adds `--via-ir` while keeping optimization disabled.
     */
    fun baselineCompilationScript(
        solFileName: String,
        remappings: List<String>,
        outDirName: String,
        outFileName: String,
        viaIr: Boolean = false
    ): String {
        val remapArgs = formatRemappings(remappings)
        val viaIrFlag = if (viaIr) "--via-ir \\\n  " else ""

        return """
        set -euo pipefail
        mkdir -p "/share/$outDirName"
        cd /share

        solc $remapArgs \
          $viaIrFlag"$solFileName" \
          ${combinedJsonOutputFlags()} \
          --allow-paths .,/share \
          > "/share/$outDirName/$outFileName"
    """.trimIndent()
    }

    /**
     * Generates script for optimized compilation with multiple optimizer runs.
     */
    fun optimizedCompilationScript(
        solFileName: String,
        remappings: List<String>,
        runsList: List<Int>,
        outDirName: String,
        viaIrConfig: SolcVersionUtil.ViaIrConfig
    ): String {
        val remapArgs = formatRemappings(remappings)
        val runsTokens = formatRunsList(runsList)
        // Build via-ir flag line - must have backslash for line continuation
        val viaIrLine = if (viaIrConfig.enabled) "${viaIrConfig.flag} \\" else "\\"

        return """
            set -euo pipefail
            mkdir -p "/share/$outDirName"
            cd /share
        
            for r in $runsTokens; do
              out_file="/share/$outDirName/${viaIrConfig.filePrefix}${'$'}r.json"
                
              solc $remapArgs \
                "$solFileName" \
                ${combinedJsonOutputFlags()} \
                --allow-paths .,/share \
                $viaIrLine
                --optimize \
                --optimize-runs "${'$'}r" \
                > "${'$'}out_file"
              
              test -f "${'$'}out_file"
            done
        """.trimIndent()
    }

    // ============================================================
    // Formatting Helpers
    // ============================================================

    private fun formatRemappings(remappings: List<String>): String {
        return remappings.joinToString(" ")
    }

    private fun formatRunsList(runsList: List<Int>): String {
        return runsList.joinToString(" ") { it.toString() }
    }

    private fun combinedJsonOutputFlags(): String {
        return "--combined-json abi,ast,bin,bin-runtime,srcmap,srcmap-runtime,userdoc,devdoc,hashes"
    }
}