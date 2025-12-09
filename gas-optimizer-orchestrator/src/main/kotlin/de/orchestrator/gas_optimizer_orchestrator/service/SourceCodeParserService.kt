package de.orchestrator.gas_optimizer_orchestrator.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.model.ContractSourceCodeResult
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText

@Service
class SourceCodeParserService(
    private val mapper: ObjectMapper
) {

    /**
     * Materialize all Solidity sources to disk so you can run crytic-compile.
     *
     * @param srcMeta  the result of EtherscanService.getContractSourceCode(...)
     * @param baseDir  base directory where files should be created
     *
     * @return list of all created file paths
     */
    fun materializeSourcesForCrytic(
        srcMeta: ContractSourceCodeResult,
        baseDir: Path
    ): List<Path> {
        if (!Files.exists(baseDir)) {
            Files.createDirectories(baseDir)
        }

        return if (srcMeta.isStandardJsonInput) {
            writeStandardJsonSources(srcMeta.sourceCode, baseDir)
        } else {
            writeSingleSolidityFile(srcMeta, baseDir)
        }
    }

    // -------------------------------------------------
    // Standard JSON input case
    // -------------------------------------------------
    private fun writeStandardJsonSources(
        standardJsonText: String,
        baseDir: Path
    ): List<Path> {
        var created = mutableListOf<Path>()

        val root: JsonNode = mapper.readTree(standardJsonText)
        val language = root["language"]?.asText().orEmpty()
        if (!language.equals("Solidity", ignoreCase = true)) {
            println("Warning: language is '$language', expected 'Solidity'")
        }

        val sourcesNode = root["sources"]
            ?: error("Standard JSON input has no 'sources' field")

        val iter = sourcesNode.fields()
        while (iter.hasNext()) {
            val (sourcePath, node) = iter.next()

            val contentNode = node["content"]
                ?: error("Source entry for $sourcePath has no 'content' field")

            val content = contentNode.asText()

            // Etherscan's key often matches import path (e.g. "MerkleRefund.sol" or "contracts/MyToken.sol")
            // We recreate that structure under baseDir so solc/crytic-compile can resolve imports.
            val normalizedPath = sourcePath.replace("\\", "/")
            val target = baseDir.resolve(normalizedPath)

            Files.createDirectories(target.parent)
            target.writeText(content)

            created.add(target)
        }

        println("Created ${created.size} Solidity files for standard-json project in $baseDir")
        return created
    }

    // -------------------------------------------------
    // Plain Solidity case (non-standard-json SourceCode)
    // -------------------------------------------------
    private fun writeSingleSolidityFile(
        srcMeta: ContractSourceCodeResult,
        baseDir: Path
    ): List<Path> {
        val fileName =
            if (srcMeta.contractName.isNotBlank()) "${srcMeta.contractName}.sol"
            else "${srcMeta.address.lowercase()}.sol"

        val target = baseDir.resolve(fileName)
        target.writeText(srcMeta.sourceCode)

        println("Created single Solidity file $target")

        return listOf(target)
    }
}