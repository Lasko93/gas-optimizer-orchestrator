package de.orchestrator.gas_optimizer_orchestrator.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

object JsonHelper {

    private val mapper = jacksonObjectMapper()

    /**
     * Extracts bytecode from a Truffle artifact JSON.
     *
     * Supports:
     *  - "bytecode": "0x..."
     *  - "bytecode": { "object": "0x..." }
     *
     * @param fieldName usually "bytecode" or "deployedBytecode"
     */
    fun extractBytecode(
        objectMapper: ObjectMapper,
        truffleArtifact: File,
        fieldName: String = "bytecode"
    ): String {
        require(truffleArtifact.exists()) { "Artifact not found: ${truffleArtifact.absolutePath}" }
        val root = objectMapper.readTree(truffleArtifact)
        return extractBytecode(root, fieldName)
    }

    fun extractBytecode(root: JsonNode, fieldName: String = "bytecode"): String {
        val node = root[fieldName]
            ?: throw IllegalStateException("Artifact missing '$fieldName' field")

        val raw = when {
            node.isTextual -> node.asText()
            node.isObject && node["object"]?.isTextual == true -> node["object"].asText()
            else -> throw IllegalStateException("Unsupported '$fieldName' schema: $node")
        }

        val normalized = if (raw.startsWith("0x")) raw else "0x$raw"
        require(normalized.length > 4) { "Extracted '$fieldName' is empty/too short" }
        return normalized
    }
    fun extractRemappingsFromStandardJson(standardJson: String): List<String> {
        return try {
            val jsonNode = mapper.readTree(standardJson)
            val settings = jsonNode.get("settings")

            // Check for remappings in settings.remappings array
            val remappingsArray = settings?.get("remappings")
            if (remappingsArray?.isArray == true) {
                remappingsArray.map { it.asText() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("Warning: Could not extract remappings from standard JSON: ${e.message}")
            emptyList()
        }
    }
}