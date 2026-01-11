package de.orchestrator.gas_optimizer_orchestrator.utils.compiler

import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.utils.bytecode.BytecodeUtil
import java.io.File

/**
 * Parses solc --combined-json output files.
 */
object CombinedJsonParser {

    /**
     * Result of parsing a combined-json file.
     */
    data class ParsedContract(
        val creationBytecode: String,
        val runtimeBytecode: String,
        val solcVersion: String?
    )

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Extracts creation and runtime bytecode from solc combined-json output.
     *
     * @param objectMapper Jackson ObjectMapper instance
     * @param combinedJsonFile The combined-json output file
     * @param contractName The contract name to extract
     * @return ParsedContract with bytecode and version info
     */
    fun parse(
        objectMapper: ObjectMapper,
        combinedJsonFile: File,
        contractName: String
    ): ParsedContract {
        require(combinedJsonFile.exists()) {
            "combined-json not found: ${combinedJsonFile.absolutePath}"
        }

        val root = objectMapper.readTree(combinedJsonFile)

        val contracts = root["contracts"]
            ?: throw IllegalStateException(
                "combined-json missing 'contracts' field: ${combinedJsonFile.name}"
            )

        val contractNode = findContractNode(contracts, contractName, combinedJsonFile.name)
        val solcVersion = root["version"]?.asText()

        return ParsedContract(
            creationBytecode = extractBytecode(contractNode, "bin", contractName, combinedJsonFile.name),
            runtimeBytecode = extractBytecode(contractNode, "bin-runtime", contractName, combinedJsonFile.name),
            solcVersion = solcVersion
        )
    }


    // ============================================================
    // Private Helpers
    // ============================================================

    private fun findContractNode(
        contracts: com.fasterxml.jackson.databind.JsonNode,
        contractName: String,
        fileName: String
    ): com.fasterxml.jackson.databind.JsonNode {
        val matches = contracts.fields().asSequence()
            .filter { it.key.endsWith(":$contractName") }
            .toList()

        if (matches.isEmpty()) {
            val available = contracts.fieldNames().asSequence().toList()
            throw IllegalStateException(
                "Contract '$contractName' not found in $fileName. Available keys: $available"
            )
        }

        // Prefer match with non-empty bin (interfaces often have empty bytecode)
        return matches
            .firstOrNull { !it.value["bin"]?.asText().isNullOrBlank() }?.value
            ?: matches.first().value
    }

    private fun extractBytecode(
        node: com.fasterxml.jackson.databind.JsonNode,
        field: String,
        contractName: String,
        fileName: String
    ): String {
        val bytecode = node[field]?.asText()
            ?: throw IllegalStateException("Missing '$field' for $contractName in $fileName")

        val normalized = BytecodeUtil.ensureHexPrefix(bytecode.trim())

        require(normalized.length > 4) {
            "Bytecode empty/too short for $contractName in $fileName"
        }

        return normalized
    }
}