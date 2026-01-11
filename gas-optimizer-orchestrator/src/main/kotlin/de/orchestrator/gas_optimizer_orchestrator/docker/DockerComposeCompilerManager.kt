package de.orchestrator.gas_optimizer_orchestrator.docker

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.utils.CompilerHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File

/**
 * Manages Docker-based Solidity compilation.
 *
 * Provides methods for:
 * - Baseline compilation (no optimization)
 * - Optimized compilation with various run settings
 * - Solc version management
 */
@Service
class DockerComposeCompilerManager(
    private val docker: DockerHelper,
    private val paths: GasOptimizerPathsProperties
) {
    private val logger = LoggerFactory.getLogger(DockerComposeCompilerManager::class.java)

    companion object {
        private const val SERVICE_NAME = "compiler"
    }

    private val hostShareDir: File
        get() = paths.externalContractsDir.toFile()

    // ============================================================
    // Public Compilation Methods
    // ============================================================

    /**
     * Compiles with various optimizer run settings.
     *
     * @param solFileName The main Solidity file name
     * @param solcVersion The compiler version to use
     * @param remappings Import remappings (e.g., "@openzeppelin/=lib/openzeppelin/")
     * @param runsList List of optimizer run values to compile with
     * @param outDirName Output directory name
     * @param viaIrRuns Whether to use the IR-based optimizer
     * @return List of compiled JSON files, one per run setting
     */
    fun compileViaSolcOptimizer(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList(),
        runsList: List<Int> = listOf(1, 200, 10_000),
        outDirName: String = "out",
        viaIrRuns: Boolean = true
    ): List<File> {
        logger.info(
            "Compiling {} with optimizer runs: {} (viaIR={})",
            solFileName, runsList, viaIrRuns
        )

        selectSolcVersion(solcVersion)
        validateCompilationSetup(solFileName)

        val hostOutDir = ensureOutputDirectory(outDirName)
        val viaIrConfig = CompilerHelper.resolveViaIrConfig(viaIrRuns, solcVersion)

        val script = CompilerHelper.optimizedCompilationScript(
            solFileName = solFileName,
            remappings = remappings,
            runsList = runsList,
            outDirName = outDirName,
            viaIrConfig = viaIrConfig
        )

        executeCompilerScript(script)

        return runsList.map { runs ->
            File(hostOutDir, "${viaIrConfig.filePrefix}$runs.json").also {
                logger.debug("Output file: {}", it.absolutePath)
            }
        }
    }

    /**
     * Compiles without optimization (baseline).
     *
     * @param solFileName The main Solidity file name
     * @param solcVersion The compiler version to use
     * @param remappings Import remappings
     * @param outFileName Output file name
     * @param outDirName Output directory name
     * @return The compiled JSON file
     */
    fun compileSolcNoOptimizeCombinedJson(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList(),
        outFileName: String = "baseline_noopt.json",
        outDirName: String = "out"
    ): File {
        logger.info("Compiling {} without optimization", solFileName)

        selectSolcVersion(solcVersion)
        validateCompilationSetup(solFileName)

        val hostOutDir = ensureOutputDirectory(outDirName)

        val script = CompilerHelper.baselineCompilationScript(
            solFileName = solFileName,
            remappings = remappings,
            outDirName = outDirName,
            outFileName = outFileName
        )

        executeCompilerScript(script)

        return File(hostOutDir, outFileName).also {
            logger.debug("Output file: {}", it.absolutePath)
        }
    }

    // ============================================================
    // Directory Management
    // ============================================================

    /**
     * Cleans the external contracts directory, removing all files.
     */
    fun cleanExternalContractsDir() {
        logger.debug("Cleaning external contracts directory: {}", hostShareDir.absolutePath)

        require(hostShareDir.exists()) {
            "Host share dir does not exist: ${hostShareDir.absolutePath}"
        }
        require(hostShareDir.isDirectory) {
            "Host share dir is not a directory: ${hostShareDir.absolutePath}"
        }

        val deletedCount = hostShareDir.listFiles().orEmpty().count { file ->
            file.deleteRecursively().also { deleted ->
                if (!deleted) logger.warn("Failed to delete: {}", file.absolutePath)
            }
        }

        logger.debug("Deleted {} items from external contracts directory", deletedCount)

        val leftover = hostShareDir.listFiles().orEmpty()
        check(leftover.isEmpty()) {
            "Failed to clean externalContracts dir. Leftover: ${leftover.joinToString { it.name }}"
        }
    }

    // ============================================================
    // Version Management
    // ============================================================

    /**
     * Selects a specific solc version in the compiler container.
     */
    fun selectSolcVersion(solcVersionRaw: String) {
        logger.debug("Selecting solc version: {}", solcVersionRaw)

        val script = CompilerHelper.solcSelectScript(solcVersionRaw)
        executeCompilerScript(script)
    }

    // ============================================================
    // Private Helpers
    // ============================================================

    private fun validateCompilationSetup(solFileName: String) {
        require(hostShareDir.exists()) {
            "Host share dir does not exist: ${hostShareDir.absolutePath}"
        }

        val hostSolFile = File(hostShareDir, solFileName)
        require(hostSolFile.exists()) {
            "Solidity file not found: ${hostSolFile.absolutePath}"
        }

        logger.debug("Validated compilation setup for: {}", solFileName)
    }

    private fun ensureOutputDirectory(outDirName: String): File {
        return File(hostShareDir, outDirName).apply {
            mkdirs()
            logger.debug("Ensured output directory: {}", absolutePath)
        }
    }

    private fun executeCompilerScript(script: String) {
        logger.trace("Executing compiler script:\n{}", script)
        docker.dockerComposeExecBash(SERVICE_NAME, script, tty = false)
    }
}