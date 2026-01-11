package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractResolution
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.effectiveSourceMeta
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.toResolvedContractInfo
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.InitialRunOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SlitherOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SolcOptimizerOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.service.etherscan.ContractResolverService
import de.orchestrator.gas_optimizer_orchestrator.utils.PrintUtil
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DemoDeployConfig(
    private val contractResolverService: ContractResolverService,
    private val initialRunOrchestrator: InitialRunOrchestrator,
    private val solcOptimizerRunOrchestrator: SolcOptimizerOrchestrator,
    private val slitherOrchestrator: SlitherOrchestrator
) {
    private val logger = LoggerFactory.getLogger(DemoDeployConfig::class.java)

    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0x3d9819210a31b4961b30ef54be2aed79b9c9cd3b"

        // Returns sealed class ContractResolution
        val resolution = contractResolverService.resolve(target)

        // Type-safe handling with when expression
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

        // Use extension property for common access to source meta
        val sourceMeta = resolution.effectiveSourceMeta

        // 1. Slither Analysis
        logger.info("=== Running Slither Analysis ===")
        val slitherReport = slitherOrchestrator.analyzeGasOptimizations(sourceMeta)

        // 2. Baseline Compilation
        // If your orchestrators still expect ResolvedContractInfo, convert it:
        val resolved = resolution.toResolvedContractInfo()

        logger.info("=== Running Baseline Compilation ===")
        val baseline = initialRunOrchestrator.runInitial(resolved)

        // 3. Optimized Compilations
        logger.info("=== Running Optimized Compilations ===")
        val optimizedResults = solcOptimizerRunOrchestrator.runSolcOptimizerRuns(resolved)

        // 4. Print Full Report
        PrintUtil.printFullReport(
            slitherReport = slitherReport,
            baseline = baseline,
            optimizedResults = optimizedResults,
            srcMeta = sourceMeta
        )
    }
}