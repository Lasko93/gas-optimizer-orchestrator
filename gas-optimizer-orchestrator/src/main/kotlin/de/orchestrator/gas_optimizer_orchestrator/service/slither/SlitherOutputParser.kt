package de.orchestrator.gas_optimizer_orchestrator.service.slither

import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherReport

internal class SlitherOutputParser(private val objectMapper: ObjectMapper) {

    fun parse(output: String): SlitherReport {
        val jsonStr = extractJson(output)
            ?: return SlitherReport.failure("No valid JSON output from Slither: $output")

        return try {
            parseJson(jsonStr)
        } catch (e: Exception) {
            logParseError(e, jsonStr)
            SlitherReport.failure("Failed to parse Slither output: ${e.message}")
        }
    }

    private fun extractJson(output: String): String? {
        val jsonStart = output.indexOf('{')
        val jsonEnd = output.lastIndexOf('}')

        return if (jsonStart != -1 && jsonEnd > jsonStart) {
            output.substring(jsonStart, jsonEnd + 1)
        } else {
            logExtractionWarning(output)
            null
        }
    }

    private fun parseJson(jsonStr: String): SlitherReport {
        val root = objectMapper.readTree(jsonStr)
        return SlitherReport(
            success = root.get("success")?.asBoolean() ?: true,
            error = root.get("error")?.asText(),
            findings = FindingsParser.parse(root)
        )
    }

    private fun logParseError(e: Exception, jsonStr: String) {
        println("Error parsing Slither JSON: ${e.message}")
        println("JSON attempted: $jsonStr")
    }

    private fun logExtractionWarning(output: String) {
        println("Warning: No valid JSON in Slither output")
        println("Cleaned output: $output")
    }
}