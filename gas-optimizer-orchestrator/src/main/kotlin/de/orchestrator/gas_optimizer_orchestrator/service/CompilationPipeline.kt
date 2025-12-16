package de.orchestrator.gas_optimizer_orchestrator.service

import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeCompilerManager
import de.orchestrator.gas_optimizer_orchestrator.model.CompiledContract
import de.orchestrator.gas_optimizer_orchestrator.model.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
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


    fun compileToTruffleAndGetDeployBytecode(
        srcMeta: ContractSourceCodeResult,
        externalContractsDir: Path,
        exportDirName: String = "crytic-export"
    ): CompiledContract {

        compilerManager.cleanExternalContractsDir()
        sourceCodeParserService.createSourceCodeArtifact(srcMeta, externalContractsDir)

        val exportDir = compilerManager.compileWithCryticTruffle(
            exportDirName = exportDirName,
            cleanExportDir = true
        )

        val artifactFile = File(exportDir, "${srcMeta.contractName}.json")
        require(artifactFile.exists()) { "Truffle artifact not found: ${artifactFile.absolutePath}" }

        val creationBytecode = JsonHelper.extractBytecode(
            objectMapper = objectMapper,
            truffleArtifact = artifactFile,
            fieldName = "bytecode"
        )

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = creationBytecode,
            constructorArgsHex = srcMeta.constructorArgumentsHex
        )

        return CompiledContract(
            artifactFile = artifactFile,
            creationBytecode = creationBytecode,
            deployBytecode = deployBytecode
        )
    }
}
