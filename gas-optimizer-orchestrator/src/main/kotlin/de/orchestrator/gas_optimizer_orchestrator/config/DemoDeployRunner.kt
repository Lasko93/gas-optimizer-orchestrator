package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractResolution
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.effectiveSourceMeta
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.InitialRunOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SlitherOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SolcOptimizerOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.service.etherscan.ContractResolverService
import de.orchestrator.gas_optimizer_orchestrator.utils.reporting.ConsoleReporter
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DemoDeployConfig(
    private val contractResolverService: ContractResolverService,
    private val initialRunOrchestrator: InitialRunOrchestrator,
    private val solcOptimizerOrchestrator: SolcOptimizerOrchestrator,
    private val slitherOrchestrator: SlitherOrchestrator
) {
    private val logger = LoggerFactory.getLogger(DemoDeployConfig::class.java)

    @Bean
    fun demoDeployRunner() = CommandLineRunner {
        val target = "0x6B175474E89094C44Da98b954EedeAC495271d0F"

        val resolution = contractResolverService.resolve(target)
        logResolutionDetails(resolution)

        val sourceMeta = resolution.effectiveSourceMeta

        // 1. Slither Analysis
        logger.info("=== Running Slither Analysis ===")
        val slitherReport = slitherOrchestrator.analyzeGasOptimizations(sourceMeta)

        // 2. Baseline Compilation (uses new run() method)
        logger.info("=== Running Baseline Compilation ===")
        val baseline = initialRunOrchestrator.run(resolution)

        // 3. Optimized Compilations (uses new run() method)
        logger.info("=== Running Optimized Compilations ===")
        val optimizedResults = solcOptimizerOrchestrator.run(resolution)

        // 4. Print Full Report
        ConsoleReporter.printFullReport(
            slitherReport = slitherReport,
            baseline = baseline,
            optimizedResults = optimizedResults,
            srcMeta = sourceMeta
        )
    }

    private fun logResolutionDetails(resolution: ContractResolution) {
        when (resolution) {
            is ContractResolution.ProxyContract -> {
                logger.info("=== Processing Proxy Contract ===")
                logger.info("   Proxy: {}", resolution.proxyAddress)
                logger.info("   Implementation: {}", resolution.implementationAddress)
                logger.info("   Contract: {}", resolution.implementationSourceMeta.contractName)
            }
            is ContractResolution.DirectContract -> {
                logger.info("=== Processing Direct Contract ===")
                logger.info("   Address: {}", resolution.address)
                logger.info("   Contract: {}", resolution.sourceMeta.contractName)
            }
        }
    }
}