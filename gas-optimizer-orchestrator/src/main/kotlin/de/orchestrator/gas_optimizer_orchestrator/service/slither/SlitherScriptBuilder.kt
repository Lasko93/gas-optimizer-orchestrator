package de.orchestrator.gas_optimizer_orchestrator.service.slither

import de.orchestrator.gas_optimizer_orchestrator.utils.CompilerHelper

internal class SlitherScriptBuilder(
    private val solFileName: String,
    private val solcVersion: String,
    private val remappings: List<String>,
    private val detectors: List<String>
) {

    companion object {
        private const val WORKING_DIR = "/share"
        private const val OUTPUT_FILE = "/share/out.json"
    }

    fun build(): String {
        val normalizedVersion = CompilerHelper.normalizeSolcVersion(solcVersion)
        return listOf(
            "cd $WORKING_DIR",
            buildSolcSelectCommand(normalizedVersion),
            buildSlitherCommand(normalizedVersion),
            buildOutputCommand()
        ).joinToString(" ; ")
    }

    private fun buildSolcSelectCommand(version: String): String =
        "solc-select use $version --always-install >/dev/null 2>&1"

    private fun buildSlitherCommand(version: String): String {
        val detectArgs = formatDetectorArgs()
        val remapArgs = formatRemappingArgs()
        return "slither $solFileName --solc-solcs-select $version $remapArgs $detectArgs --json $OUTPUT_FILE 2>/dev/null || true"
    }

    private fun buildOutputCommand(): String =
        "cat $OUTPUT_FILE 2>/dev/null"

    private fun formatDetectorArgs(): String =
        if (detectors.isNotEmpty()) "--detect ${detectors.joinToString(",")}" else ""

    private fun formatRemappingArgs(): String =
        if (remappings.isNotEmpty()) "--solc-remaps ${remappings.joinToString(",")}" else ""
}