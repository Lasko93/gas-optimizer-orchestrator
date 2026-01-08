package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.orchestrators.InitialRunOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SlitherOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SolcOptimizerOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.service.ContractResolverService
import de.orchestrator.gas_optimizer_orchestrator.utils.PrintUtil
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

    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0xC02aaA39b223FE8D0A0e5C4F27eAD9083C756Cc2"
        // Single resolution step handles proxy vs direct
        val resolved = contractResolverService.resolveContract(target)

        if (resolved.isProxy) {
            println("=== Processing Proxy Contract ===")
            println("   Proxy: ${resolved.proxyAddress}")
            println("   Implementation: ${resolved.implementationAddress}")
            println("   Implementation Contract: ${resolved.contractName}")
        } else {
            println("=== Processing Direct Contract ===")
            println("   Address: ${resolved.implementationAddress}")
            println("   Contract: ${resolved.contractName}")
        }

        // 1. Slither Analysis (on implementation source)
        println("\n=== Running Slither Analysis ===")
        val slitherReport = slitherOrchestrator.analyzeGasOptimizations(
            resolved.implementationSourceMeta
        )

        // 2. Baseline Compilation (compile implementation)
        println("\n=== Running Baseline Compilation ===")
        val baseline = initialRunOrchestrator.runInitial(resolved)

        // 3. Optimized Compilations
        println("\n=== Running Optimized Compilations ===")
        val optimizedResults = solcOptimizerRunOrchestrator.runSolcOptimizerRuns(resolved)

        // 4. Print Full Report
        PrintUtil.printFullReport(
            slitherReport = slitherReport,
            baseline = baseline,
            optimizedResults = optimizedResults,
            srcMeta = resolved.implementationSourceMeta
        )
    }
}