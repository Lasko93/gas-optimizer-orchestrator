package de.orchestrator.gas_optimizer_orchestrator.utils

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import de.orchestrator.gas_optimizer_orchestrator.exceptions.EtherScanException

object EtherScanHelper {

    /**
     * Etherscan wraps standard-json SourceCode like:
     *   "{{\n  \"language\": \"Solidity\", ... }}"
     * We want plain `{ "language": "Solidity", ... }`.
     */
    fun normalizeEtherscanSourceField(raw: String): String {
        var s = raw.trim()

        // Most of the quoting is already removed by JsonNode.asText(),
        // but Etherscan still leaves a `{{ ... }}` wrapper for standard-json.
        if (s.startsWith("{{") && s.endsWith("}}")) {
            s = s.substring(1, s.length - 1).trim()
        }

        return s
    }

    /**
     * Detects if a source string looks like a Solidity standard-json input.
     */
    fun looksLikeStandardJson(src: String): Boolean {
        val t = src.trimStart()
        return t.startsWith("{") &&
                t.contains("\"language\"") &&
                t.contains("\"sources\"")
    }

    /**
     * Generic helper to enforce Etherscan "status == 1" contract.
     */
    fun ensureOk(root: JsonNode, context: String) {
        val status = root["status"]?.asText() ?: "0"
        if (status != "1") {
            throw EtherScanException(
                status,
                root["message"]?.asText() ?: "Etherscan error during $context"
            )
        }
    }

    /**
     * For endpoints where you expect at least one result (e.g. getsourcecode).
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
     * For endpoints where an empty array is acceptable,
     * but you still require `result` to be an array.
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

    /**
     * Convenience: safely read a text field, defaulting to "".
     */
    fun optionalText(node: JsonNode, field: String): String =
        node[field]?.asText().orEmpty()

    /**
     * Extracts the 4-byte method selector (as "0x" + 8 hex chars)
     * from the transaction input, or null if input is empty.
     */
    fun extractMethodSelector(input: String?): String? {
        if (input.isNullOrBlank() || input == "0x") return null
        // First 4 bytes of function selector => "0x" + 8 hex chars
        return input.take(10)
    }

    /**
     * Normalizes addresses consistently (currently just trimming).
     * In the future you can also lower-case, checksum-validate, etc.
     */
    fun normalizeAddress(address: String): String = address.trim()
}
