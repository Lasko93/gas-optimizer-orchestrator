package de.orchestrator.gas_optimizer_orchestrator.orchestrators

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeCompilerManager
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherReport
import de.orchestrator.gas_optimizer_orchestrator.service.slither.SlitherService
import de.orchestrator.gas_optimizer_orchestrator.service.compilation.SourceCodeParserService
import org.springframework.stereotype.Service

@Service
class SlitherOrchestrator(
    private val slitherService: SlitherService,
    private val sourceCodeParserService: SourceCodeParserService,
    private val compilerManager: DockerComposeCompilerManager,
    private val paths: GasOptimizerPathsProperties
) {

    fun analyzeGasOptimizations(srcMeta: ContractSourceCodeResult): SlitherReport {
        prepareWorkspace(srcMeta)
        return executeAnalysis(srcMeta)
    }

    private fun prepareWorkspace(srcMeta: ContractSourceCodeResult) {
        compilerManager.cleanExternalContractsDir()
        sourceCodeParserService.createSourceCodeArtifact(srcMeta, paths.externalContractsDir)
    }

    private fun executeAnalysis(srcMeta: ContractSourceCodeResult): SlitherReport =
        slitherService.analyzeForGasOptimizations(
            solFileName = "${srcMeta.contractName}.sol",
            solcVersion = srcMeta.compilerVersion,
            remappings = srcMeta.remappings
        )
}