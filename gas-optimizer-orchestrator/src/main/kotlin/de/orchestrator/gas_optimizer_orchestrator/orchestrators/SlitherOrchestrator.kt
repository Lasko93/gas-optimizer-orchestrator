package de.orchestrator.gas_optimizer_orchestrator.orchestrators

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.service.SlitherService
import de.orchestrator.gas_optimizer_orchestrator.service.SourceCodeParserService
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeCompilerManager
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherReport
import org.springframework.stereotype.Service

@Service
class SlitherOrchestrator(
    private val slitherService: SlitherService,
    private val sourceCodeParserService: SourceCodeParserService,
    private val compilerManager: DockerComposeCompilerManager,
    private val paths: GasOptimizerPathsProperties
) {

    /**
     * Analysiert einen Contract für Gas-Optimierungen
     */
    fun analyzeGasOptimizations(srcMeta: ContractSourceCodeResult): SlitherReport {

        compilerManager.cleanExternalContractsDir()
        sourceCodeParserService.createSourceCodeArtifact(srcMeta, paths.externalContractsDir)

        return slitherService.analyzeForGasOptimizations(
            solFileName = "${srcMeta.contractName}.sol",
            solcVersion = srcMeta.compilerVersion,
            remappings = srcMeta.remappings
        )
    }
}