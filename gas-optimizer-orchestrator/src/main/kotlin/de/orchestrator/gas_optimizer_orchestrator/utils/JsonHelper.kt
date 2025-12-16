package de.orchestrator.gas_optimizer_orchestrator.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

object JsonHelper {

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
}