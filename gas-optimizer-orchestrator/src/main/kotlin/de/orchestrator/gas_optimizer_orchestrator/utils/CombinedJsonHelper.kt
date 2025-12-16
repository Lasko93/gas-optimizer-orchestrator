package de.orchestrator.gas_optimizer_orchestrator.utils

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File

object CombinedJsonHelper {

    /**
     * Extracts "bin" and "bin-runtime" from solc --combined-json output for the given contract name.
     *
     * Picks key ending with ":<contractName>" and ensures bin is not blank.
     */
    fun extractCreationAndRuntime(
        objectMapper: ObjectMapper,
        combinedJsonFile: File,
        contractName: String
    ): Triple<String, String, String?> {
        require(combinedJsonFile.exists()) { "combined-json not found: ${combinedJsonFile.absolutePath}" }

        val root = objectMapper.readTree(combinedJsonFile)
        val contracts = root["contracts"]
            ?: throw IllegalStateException("combined-json missing 'contracts' field: ${combinedJsonFile.name}")

        val matches = contracts.fields().asSequence()
            .filter { it.key.endsWith(":$contractName") }
            .toList()

        if (matches.isEmpty()) {
            val available = contracts.fieldNames().asSequence().toList()
            throw IllegalStateException(
                "Contract '$contractName' not found in ${combinedJsonFile.name}. Available keys: $available"
            )
        }

        // Prefer the match that has non-empty bin (interfaces/context often have "")
        val chosen = matches.firstOrNull { !it.value["bin"]?.asText().isNullOrBlank() }
            ?: matches.first()

        val node = chosen.value

        val bin = node["bin"]?.asText()
            ?: throw IllegalStateException("Missing 'bin' for ${chosen.key} in ${combinedJsonFile.name}")

        val binRuntime = node["bin-runtime"]?.asText()
            ?: throw IllegalStateException("Missing 'bin-runtime' for ${chosen.key} in ${combinedJsonFile.name}")

        fun norm(h: String): String {
            val hex = h.trim()
            val with0x = if (hex.startsWith("0x")) hex else "0x$hex"
            require(with0x.length > 4) { "Bytecode empty/too short for ${chosen.key} in ${combinedJsonFile.name}" }
            return with0x
        }

        val solcVersion = root["version"]?.asText()

        return Triple(norm(bin), norm(binRuntime), solcVersion)
    }
}
