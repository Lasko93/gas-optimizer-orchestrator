package de.orchestrator.gas_optimizer_orchestrator.service.slither

import com.fasterxml.jackson.databind.JsonNode
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherElement
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherFinding
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SourceMapping

internal object FindingsParser {

    fun parse(root: JsonNode): List<SlitherFinding> {
        val detectors = root.path("results").path("detectors")
        if (!detectors.isArray) return emptyList()

        return detectors.map { parseFinding(it) }
    }

    private fun parseFinding(node: JsonNode): SlitherFinding =
        SlitherFinding(
            check = node.textOrDefault("check", "unknown"),
            impact = node.textOrDefault("impact", "unknown"),
            confidence = node.textOrDefault("confidence", "unknown"),
            description = node.textOrDefault("description", ""),
            firstMarkdownElement = node.get("first_markdown_element")?.asText(),
            elements = ElementsParser.parse(node.get("elements"))
        )

    private fun JsonNode.textOrDefault(field: String, default: String): String =
        get(field)?.asText() ?: default
}

internal object ElementsParser {

    fun parse(node: JsonNode?): List<SlitherElement> {
        if (node == null || !node.isArray) return emptyList()
        return node.mapNotNull { parseElement(it) }
    }

    private fun parseElement(node: JsonNode): SlitherElement? =
        try {
            SlitherElement(
                type = node.get("type")?.asText() ?: "unknown",
                name = node.get("name")?.asText() ?: "",
                sourceMapping = parseSourceMapping(node.get("source_mapping"))
            )
        } catch (e: Exception) {
            null
        }

    private fun parseSourceMapping(node: JsonNode?): SourceMapping? =
        node?.let {
            SourceMapping(
                filename = it.get("filename_relative")?.asText() ?: "",
                start = it.get("start")?.asInt() ?: 0,
                length = it.get("length")?.asInt() ?: 0,
                lines = parseLines(it.get("lines"))
            )
        }

    private fun parseLines(node: JsonNode?): List<Int> =
        node?.mapNotNull { it.asInt() } ?: emptyList()
}