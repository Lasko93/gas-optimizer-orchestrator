package de.orchestrator.gas_optimizer_orchestrator.service.compilation

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.iterator
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

@Service
class SourceCodeParserService(
    private val objectMapper: ObjectMapper
) {

    fun createSourceCodeArtifact(
        srcMeta: ContractSourceCodeResult,
        baseDir: Path
    ): List<Path> {
        if (!Files.exists(baseDir)) Files.createDirectories(baseDir)
        require(baseDir.exists() && baseDir.isDirectory()) { "baseDir is not a directory: $baseDir" }

        val entryFile = baseDir.resolve("${srcMeta.contractName}.sol")

        val written = if (srcMeta.isStandardJsonInput) {
            val sources = parseStandardJsonSources(srcMeta.sourceCode)
            val writtenSources = writeStandardJsonSources(sources, baseDir)

            // pick the source file that actually defines the contract (may be nested)
            val definingRelPath = findDefiningSourcePath(sources, srcMeta.contractName)
                ?: sources.keys.firstOrNull()
                ?: error("No sources in standard-json input")

            // If the defining file is already exactly the entry file, don't overwrite it.
            if (definingRelPath.replace('\\', '/') != "${srcMeta.contractName}.sol") {
                writeImportStub(entryFile, definingRelPath, sources[definingRelPath].orEmpty())
            }

            writtenSources
        } else {
            // single file => always place at root, named after the contract
            writeSingleSolidityFile(entryFile, srcMeta.sourceCode)
            listOf(entryFile)
        }

        // Ensure entry exists for downstream compiler: /share/<ContractName>.sol
        require(Files.exists(entryFile)) {
            "Entry solidity file was not created: $entryFile"
        }

        return (listOf(entryFile) + written).distinct()
    }

    // ------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------

    private fun writeSingleSolidityFile(entryFile: Path, source: String) {
        entryFile.parent?.createDirectories()
        entryFile.writeText(source)
    }

    private fun writeStandardJsonSources(sources: Map<String, String>, baseDir: Path): List<Path> {
        val written = mutableListOf<Path>()
        for ((rel, content) in sources) {
            val normalizedRel = rel.replace('\\', '/')
            val target = baseDir.resolve(normalizedRel)
            target.parent?.createDirectories()
            target.writeText(content)
            written.add(target)
        }
        return written
    }

    /**
     * Creates a tiny root file that imports the real source file (possibly nested).
     * This guarantees the compiler can always compile: /share/<ContractName>.sol
     */
    private fun writeImportStub(entryFile: Path, definingRelPath: String, definingContent: String) {
        val importPath = "./" + definingRelPath.replace('\\', '/')
        val pragma = extractPragmaSolidity(definingContent)

        val stub = buildString {
            appendLine("// SPDX-License-Identifier: UNLICENSED")
            if (pragma != null) appendLine(pragma)
            appendLine("import \"$importPath\";")
            appendLine()
            appendLine("// This file is generated as a stable compile entrypoint.")
        }

        entryFile.parent?.createDirectories()
        entryFile.writeText(stub)
    }

    // ------------------------------------------------------------
    // Parsing standard-json input
    // ------------------------------------------------------------

    private fun parseStandardJsonSources(sourceCodeField: String): Map<String, String> {
        val node = parsePossiblyEscapedJson(sourceCodeField)

        val sourcesNode = node.get("sources")
            ?: error("standard-json input missing 'sources'")

        val result = LinkedHashMap<String, String>()
        val fields = sourcesNode.fields()
        while (fields.hasNext()) {
            val (path, v) = fields.next()
            val content = v.get("content")?.asText() ?: ""
            result[path] = content
        }
        return result
    }

    /**
     * Etherscan-style standard-json sometimes comes as a JSON string containing JSON.
     * This handles both raw object and escaped-string forms.
     */
    private fun parsePossiblyEscapedJson(raw: String): JsonNode {
        val trimmed = raw.trim()

        return if (trimmed.startsWith("{")) {
            objectMapper.readTree(trimmed)
        } else {
            // try: it's a JSON string containing JSON
            val asString = objectMapper.readValue(trimmed, String::class.java)
            objectMapper.readTree(asString)
        }
    }

    // ------------------------------------------------------------
    // Finding the defining source for a contract
    // ------------------------------------------------------------

    private fun findDefiningSourcePath(sources: Map<String, String>, contractName: String): String? {
        val re = Regex("""\b(?:abstract\s+)?(?:contract|library|interface)\s+${Regex.escape(contractName)}\b""")
        for ((path, content) in sources) {
            val stripped = stripSolidityComments(content)
            if (re.containsMatchIn(stripped)) return path
        }
        return null
    }

    private fun stripSolidityComments(src: String): String {
        val noBlock = Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL)).replace(src, " ")
        return Regex("//.*?$", setOf(RegexOption.MULTILINE)).replace(noBlock, " ")
    }

    private fun extractPragmaSolidity(src: String): String? {
        // first `pragma solidity ...;`
        val m = Regex("""(?m)^\s*pragma\s+solidity\s+[^;]+;""").find(src)
        return m?.value?.trim()
    }
}
