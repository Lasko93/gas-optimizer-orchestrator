package de.orchestrator.gas_optimizer_orchestrator.service.compilation

import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeCompilerManager
import de.orchestrator.gas_optimizer_orchestrator.model.compilation.CompiledContract
import de.orchestrator.gas_optimizer_orchestrator.model.compilation.CompiledIrRun
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.OrchestratorConstants.DEFAULT_OPTIMIZER_RUNS
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.OrchestratorConstants.DEFAULT_OUT_DIR
import de.orchestrator.gas_optimizer_orchestrator.utils.CombinedJsonHelper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path

/**
 * Pipeline for compiling Solidity contracts with various optimization settings.
 *
 * Handles the full compilation workflow:
 * 1. Preparing source files in the shared directory
 * 2. Invoking the Docker-based compiler
 * 3. Parsing compilation artifacts
 */
@Service
class CompilationPipeline(
    private val compilerManager: DockerComposeCompilerManager,
    private val sourceCodeParserService: SourceCodeParserService,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(CompilationPipeline::class.java)

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Compiles a contract without optimization (baseline).
     */
    fun compileBaseline(
        srcMeta: ContractSourceCodeResult,
        externalContractsDir: Path,
        outDirName: String = DEFAULT_OUT_DIR,
        outFileName: String = "${srcMeta.contractName}_baseline.json"
    ): CompiledContract {
        logger.info("Compiling baseline (no optimization): {}", srcMeta.contractName)

        prepareSourceFiles(srcMeta, externalContractsDir)

        val combinedJson = compilerManager.compileSolcNoOptimizeCombinedJson(
            solFileName = "${srcMeta.contractName}.sol",
            solcVersion = srcMeta.compilerVersion,
            outDirName = outDirName,
            outFileName = outFileName,
            remappings = srcMeta.remappings
        )

        return parseCompiledContract(combinedJson, srcMeta.contractName)
    }

    /**
     * Compiles a contract with various optimizer run settings.
     */
    fun compileOptimized(
        srcMeta: ContractSourceCodeResult,
        externalContractsDir: Path,
        runsList: List<Int> = DEFAULT_OPTIMIZER_RUNS,
        outDirName: String = DEFAULT_OUT_DIR,
        viaIr: Boolean = false
    ): List<CompiledIrRun> {
        logger.info(
            "Compiling with optimizer runs {}: {} (viaIR={})",
            runsList, srcMeta.contractName, viaIr
        )

        prepareSourceFiles(srcMeta, externalContractsDir)

        val combinedJsonFiles = compilerManager.compileViaSolcOptimizer(
            solFileName = "${srcMeta.contractName}.sol",
            runsList = runsList,
            outDirName = outDirName,
            solcVersion = srcMeta.compilerVersion,
            remappings = srcMeta.remappings,
            viaIrRuns = viaIr
        )

        return parseOptimizedResults(combinedJsonFiles, runsList, srcMeta.contractName)
    }

    // ============================================================
    // Helper Methods
    // ============================================================

    private fun prepareSourceFiles(srcMeta: ContractSourceCodeResult, externalContractsDir: Path) {
        logger.debug("Preparing source files for: {}", srcMeta.contractName)
        compilerManager.cleanExternalContractsDir()
        sourceCodeParserService.createSourceCodeArtifact(srcMeta, externalContractsDir)
    }

    private fun parseCompiledContract(combinedJsonFile: File, contractName: String): CompiledContract {
        val (creation, runtime, _) = CombinedJsonHelper.extractCreationAndRuntime(
            objectMapper = objectMapper,
            combinedJsonFile = combinedJsonFile,
            contractName = contractName
        )

        logger.debug(
            "Parsed contract: {} (creation: {} bytes, runtime: {} bytes)",
            contractName,
            creation.length / 2,
            runtime.length / 2
        )

        return CompiledContract(
            artifactFile = combinedJsonFile,
            creationBytecode = creation,
            runtimeBytecode = runtime
        )
    }

    private fun parseOptimizedResults(
        combinedJsonFiles: List<File>,
        runsList: List<Int>,
        contractName: String
    ): List<CompiledIrRun> {
        return runsList.zip(combinedJsonFiles).map { (runs, file) ->
            val (creation, runtime, solcVersion) = CombinedJsonHelper.extractCreationAndRuntime(
                objectMapper = objectMapper,
                combinedJsonFile = file,
                contractName = contractName
            )

            logger.debug("Parsed optimized run {}: {} bytes runtime", runs, runtime.length / 2)

            CompiledIrRun(
                optimizeRuns = runs,
                combinedJsonFile = file,
                creationBytecode = creation,
                runtimeBytecode = runtime,
                solcVersion = solcVersion
            )
        }
    }
}