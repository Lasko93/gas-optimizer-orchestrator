package de.orchestrator.gas_optimizer_orchestrator.docker

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.utils.compiler.CompilerScriptBuilder
import de.orchestrator.gas_optimizer_orchestrator.utils.compiler.SolcVersionUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.docker.DockerCommandExecutor
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
    private val dockerCommandExecutor: DockerCommandExecutor,
    private val paths: GasOptimizerPathsProperties
) {
    private val logger = LoggerFactory.getLogger(DockerComposeCompilerManager::class.java)

    companion object {
        private const val SERVICE_NAME = "compiler"
        private const val DEFAULT_OUT_DIR = "out"
        private const val DEFAULT_BASELINE_FILENAME = "baseline_noopt.json"
        private val DEFAULT_OPTIMIZER_RUNS = listOf(1, 200, 10_000)
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
        runsList: List<Int> = DEFAULT_OPTIMIZER_RUNS,
        outDirName: String = DEFAULT_OUT_DIR,
        viaIrRuns: Boolean = true
    ): List<File> {
        logger.info("Compiling {} with optimizer runs: {} (viaIR={})", solFileName, runsList, viaIrRuns)

        prepareCompilation(solcVersion, solFileName)

        val hostOutDir = ensureOutputDirectory(outDirName)
        val viaIrConfig = SolcVersionUtil.resolveViaIrConfig(viaIrRuns, solcVersion)

        val script = CompilerScriptBuilder.optimizedCompilationScript(
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
        outFileName: String = DEFAULT_BASELINE_FILENAME,
        outDirName: String = DEFAULT_OUT_DIR
    ): File {
        logger.info("Compiling {} without optimization", solFileName)

        prepareCompilation(solcVersion, solFileName)

        val hostOutDir = ensureOutputDirectory(outDirName)

        val script = CompilerScriptBuilder.baselineCompilationScript(
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

        validateHostShareDir()

        val files = hostShareDir.listFiles().orEmpty()
        val deletedCount = files.count { file ->
            file.deleteRecursively().also { deleted ->
                if (!deleted) logger.warn("Failed to delete: {}", file.absolutePath)
            }
        }

        logger.debug("Deleted {} items from external contracts directory", deletedCount)

        validateDirectoryIsEmpty()
    }

    // ============================================================
    // Version Management
    // ============================================================

    /**
     * Selects a specific solc version in the compiler container.
     */
    fun selectSolcVersion(solcVersionRaw: String) {
        logger.debug("Selecting solc version: {}", solcVersionRaw)

        val script = CompilerScriptBuilder.solcSelectScript(solcVersionRaw)
        executeCompilerScript(script)
    }

    // ============================================================
    // Private Helpers
    // ============================================================

    private fun prepareCompilation(solcVersion: String, solFileName: String) {
        selectSolcVersion(solcVersion)
        validateCompilationSetup(solFileName)
    }

    private fun validateHostShareDir() {
        require(hostShareDir.exists()) {
            "Host share dir does not exist: ${hostShareDir.absolutePath}"
        }
        require(hostShareDir.isDirectory) {
            "Host share dir is not a directory: ${hostShareDir.absolutePath}"
        }
    }

    private fun validateDirectoryIsEmpty() {
        val leftover = hostShareDir.listFiles().orEmpty()
        check(leftover.isEmpty()) {
            "Failed to clean externalContracts dir. Leftover: ${leftover.joinToString { it.name }}"
        }
    }

    private fun validateCompilationSetup(solFileName: String) {
        validateHostShareDir()

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
        dockerCommandExecutor.composeExecBash(SERVICE_NAME, script, tty = false)
    }
}