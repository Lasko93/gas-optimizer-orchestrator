package de.orchestrator.gas_optimizer_orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerHelper
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherElement
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherFinding
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherReport
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SourceMapping
import de.orchestrator.gas_optimizer_orchestrator.utils.CompilerHelper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SlitherService(
    private val docker: DockerHelper,
    private val objectMapper: ObjectMapper,
    @Value("\${docker.compiler.service-name:compiler}") private val serviceName: String
) {

    companion object {
        // Gas-relevante Detektoren
        val GAS_DETECTORS = listOf(
            "constable-states",          // State variables that could be constant
            "external-function",         // Public functions that could be external
            "costly-loop",               // Costly operations in loops
            "cache-array-length",        // Array length not cached in loop
            "immutable-states",          // State variables that could be immutable
            "dead-code",                 // Unused functions
            "unused-state",              // Unused state variables
        )

        val ALL_DETECTORS = emptyList<String>() // leer = alle
    }

    /**
     * Führt Slither-Analyse für Gas-Optimierungen aus
     */
    fun analyzeForGasOptimizations(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList()
    ): SlitherReport {
        return runSlither(
            solFileName = solFileName,
            solcVersion = solcVersion,
            remappings = remappings,
            detectors = GAS_DETECTORS
        )
    }

    private fun runSlither(
        solFileName: String,
        solcVersion: String,
        remappings: List<String>,
        detectors: List<String>
    ): SlitherReport {
        val normalizedVersion = CompilerHelper.normalizeSolcVersion(solcVersion)

        val detectArgs = if (detectors.isNotEmpty()) "--detect ${detectors.joinToString(",")}" else ""
        val remapArgs = if (remappings.isNotEmpty()) "--solc-remaps ${remappings.joinToString(",")}" else ""

        val commands = listOf(
            "cd /share",
            "solc-select use $normalizedVersion --always-install >/dev/null 2>&1",
            "slither $solFileName --solc-solcs-select $normalizedVersion $remapArgs $detectArgs --json /share/out.json 2>/dev/null || true",
            "cat /share/out.json 2>/dev/null"
        )

        val script = commands.joinToString(" ; ")

        val output = docker.dockerComposeExecBashWithOutput(
            service = serviceName,
            bashScript = script,
            tty = false,
            failOnError = false
        )

        val cleanedOutput = output.lines()
            .filterNot { it.contains("level=warning") && it.contains("variable is not set") }
            .joinToString("\n")
            .trim()

        // Wenn kein Output, gib Fehler-Report zurück
        if (cleanedOutput.isEmpty() || !cleanedOutput.contains("{")) {
            return SlitherReport(
                success = false,
                error = "Slither produced no JSON output. Raw: $cleanedOutput",
                findings = emptyList()
            )
        }

        return parseSlitherOutput(cleanedOutput)
    }

    private fun parseSlitherOutput(output: String): SlitherReport {
        // Filter out any non-JSON lines (docker warnings, etc.)
        val cleanedOutput = output.lines()
            .filterNot { line ->
                line.contains("level=warning") ||
                        line.contains("Switched global version") ||
                        line.contains("solc, the solidity compiler") ||
                        line.contains("Version:") ||
                        line.trim().isEmpty()
            }
            .joinToString("\n")
            .trim()

        // Finde das JSON im Output
        val jsonStart = cleanedOutput.indexOf('{')
        val jsonEnd = cleanedOutput.lastIndexOf('}')

        if (jsonStart == -1 || jsonEnd == -1 || jsonEnd <= jsonStart) {
            println("Warning: No valid JSON in Slither output")
            println("Cleaned output: $cleanedOutput")
            return SlitherReport(
                success = false,
                error = "No valid JSON output from Slither: $cleanedOutput",
                findings = emptyList()
            )
        }

        val jsonStr = cleanedOutput.substring(jsonStart, jsonEnd + 1)

        return try {
            val root = objectMapper.readTree(jsonStr)

            val success = root.get("success")?.asBoolean() ?: true
            val error = root.get("error")?.asText()

            val findings = mutableListOf<SlitherFinding>()

            val detectors = root.get("results")?.get("detectors")
            if (detectors != null && detectors.isArray) {
                for (detector in detectors) {
                    findings.add(
                        SlitherFinding(
                            check = detector.get("check")?.asText() ?: "unknown",
                            impact = detector.get("impact")?.asText() ?: "unknown",
                            confidence = detector.get("confidence")?.asText() ?: "unknown",
                            description = detector.get("description")?.asText() ?: "",
                            firstMarkdownElement = detector.get("first_markdown_element")?.asText(),
                            elements = parseElements(detector.get("elements"))
                        )
                    )
                }
            }

            SlitherReport(
                success = success,
                error = error,
                findings = findings
            )
        } catch (e: Exception) {
            println("Error parsing Slither JSON: ${e.message}")
            println("JSON attempted: $jsonStr")
            SlitherReport(
                success = false,
                error = "Failed to parse Slither output: ${e.message}",
                findings = emptyList()
            )
        }
    }

    private fun parseElements(elementsNode: com.fasterxml.jackson.databind.JsonNode?): List<SlitherElement> {
        if (elementsNode == null || !elementsNode.isArray) return emptyList()

        return elementsNode.mapNotNull { el ->
            try {
                SlitherElement(
                    type = el.get("type")?.asText() ?: "unknown",
                    name = el.get("name")?.asText() ?: "",
                    sourceMapping = el.get("source_mapping")?.let { sm ->
                        SourceMapping(
                            filename = sm.get("filename_relative")?.asText() ?: "",
                            start = sm.get("start")?.asInt() ?: 0,
                            length = sm.get("length")?.asInt() ?: 0,
                            lines = sm.get("lines")?.map { it.asInt() } ?: emptyList()
                        )
                    }
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}