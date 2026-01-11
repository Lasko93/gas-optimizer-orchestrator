package de.orchestrator.gas_optimizer_orchestrator.utils.etherscan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.exceptions.EtherScanException
import org.slf4j.LoggerFactory

/**
 * Utilities for parsing and validating Etherscan API responses.
 */
object EtherscanResponseHelper {

    private val logger = LoggerFactory.getLogger(EtherscanResponseHelper::class.java)
    private val mapper = jacksonObjectMapper()

    // ============================================================
    // Response Validation
    // ============================================================

    /**
     * Ensures an Etherscan response has status == "1" (success).
     *
     * @throws EtherScanException if status is not "1"
     */
    fun ensureSuccess(root: JsonNode, context: String) {
        val status = root["status"]?.asText() ?: "0"

        if (status != "1") {
            val message = root["message"]?.asText() ?: "Etherscan error during $context"
            throw EtherScanException(status, message)
        }
    }

    /**
     * Extracts result array, requiring at least one element.
     *
     * @throws EtherScanException if result is empty or not an array
     */
    fun requireNonEmptyResultArray(root: JsonNode, context: String): ArrayNode {
        val result = root["result"]

        if (result == null || !result.isArray || result.size() == 0) {
            throw EtherScanException(
                "0",
                "Empty or invalid result in Etherscan response during $context"
            )
        }

        return result as ArrayNode
    }

    /**
     * Extracts result array (may be empty).
     *
     * @throws EtherScanException if result is not an array
     */
    fun requireResultArray(root: JsonNode, context: String): ArrayNode {
        val result = root["result"]

        if (result == null || !result.isArray) {
            throw EtherScanException(
                "0",
                "Invalid result in Etherscan response during $context"
            )
        }

        return result as ArrayNode
    }

    // ============================================================
    // Field Extraction
    // ============================================================

    /**
     * Safely reads a text field, defaulting to empty string.
     */
    fun optionalText(node: JsonNode, field: String): String {
        return node[field]?.asText().orEmpty()
    }


    // ============================================================
    // Transaction Helpers
    // ============================================================

    /**
     * Extracts the 4-byte method selector from transaction input.
     *
     * @return "0x" + 8 hex chars, or null if input is empty
     */
    fun extractMethodSelector(input: String?): String? {
        if (input.isNullOrBlank() || input == "0x") return null
        return input.take(10)
    }

    // ============================================================
    // Address Helpers
    // ============================================================

    /**
     * Normalizes an Ethereum address.
     */
    fun normalizeAddress(address: String): String {
        return address.trim().lowercase()
    }

    // ============================================================
    // Source Code Helpers
    // ============================================================

    /**
     * Normalizes Etherscan's source code field.
     *
     * Etherscan wraps standard-json with double braces: "{{ ... }}"
     * This removes the outer braces.
     */
    fun normalizeSourceField(raw: String): String {
        var source = raw.trim()

        if (source.startsWith("{{") && source.endsWith("}}")) {
            source = source.substring(1, source.length - 1).trim()
        }

        return source
    }

    /**
     * Detects if source code looks like Solidity standard-json input.
     */
    fun isStandardJsonInput(source: String): Boolean {
        val trimmed = source.trimStart()
        return trimmed.startsWith("{") &&
                trimmed.contains("\"language\"") &&
                trimmed.contains("\"sources\"")
    }

    /**
     * Extracts remappings from standard-json input.
     */
    fun extractRemappings(standardJson: String): List<String> {
        return try {
            val jsonNode = mapper.readTree(standardJson)
            val remappingsArray = jsonNode["settings"]?.get("remappings")

            if (remappingsArray?.isArray == true) {
                remappingsArray.map { it.asText() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            logger.warn("Could not extract remappings from standard JSON: {}", e.message)
            emptyList()
        }
    }
}