package de.orchestrator.gas_optimizer_orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeCompilerManager
import de.orchestrator.gas_optimizer_orchestrator.model.CompiledContract
import de.orchestrator.gas_optimizer_orchestrator.model.CompiledIrRun
import de.orchestrator.gas_optimizer_orchestrator.model.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.CombinedJsonHelper
import de.orchestrator.gas_optimizer_orchestrator.utils.JsonHelper
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Path

@Service
class CompilationPipeline(
    private val compilerManager: DockerComposeCompilerManager,
    private val sourceCodeParserService: SourceCodeParserService,
    private val objectMapper: ObjectMapper
) {


    fun compileBaselineNoOptimize(
        srcMeta: ContractSourceCodeResult,
        externalContractsDir: Path,
        outDirName: String = "out",
        outFileName: String = "baseline_noopt.json"
    ): CompiledContract {

        compilerManager.cleanExternalContractsDir()
        sourceCodeParserService.createSourceCodeArtifact(srcMeta, externalContractsDir)

        val combinedJson = compilerManager.compileSolcNoOptimizeCombinedJson(
            solFileName = "${srcMeta.contractName}.sol",
            solcVersion = srcMeta.compilerVersion,
            outDirName = outDirName,
            outFileName = outFileName
        )

        val (creation, runtime, solcVersion) = CombinedJsonHelper.extractCreationAndRuntime(
            objectMapper = objectMapper,
            combinedJsonFile = combinedJson,
            contractName = srcMeta.contractName
        )

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = creation,
            constructorArgsHex = srcMeta.constructorArgumentsHex
        )

        return CompiledContract(
            artifactFile = combinedJson,
            creationBytecode = creation,
            deployBytecode = deployBytecode
        )
    }

    fun compileViaIrRuns(
        srcMeta: ContractSourceCodeResult,
        externalContractsDir: Path,
        runsList: List<Int> = listOf(1, 200, 10_000),
        outDirName: String = "out",
    ): List<CompiledIrRun> {

        compilerManager.cleanExternalContractsDir()
        sourceCodeParserService.createSourceCodeArtifact(srcMeta, externalContractsDir)


        val combinedJsonFiles: List<File> = compilerManager.compileViaIrRunsCombinedJson(
            solFileName = srcMeta.contractName+".sol",
            runsList = runsList,
            outDirName = outDirName,
            solcVersion = srcMeta.compilerVersion
        )

        return runsList.zip(combinedJsonFiles).map { (runs, file) ->
            val (creation, runtime, solcVersion) = CombinedJsonHelper.extractCreationAndRuntime(
                objectMapper = objectMapper,
                combinedJsonFile = file,
                contractName = srcMeta.contractName
            )

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

