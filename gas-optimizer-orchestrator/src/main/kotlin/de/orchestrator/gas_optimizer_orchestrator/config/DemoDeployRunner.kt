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

        // 0x3d9819210A31b4961b30EF54bE2aeD79B9c9Cd3B fix the gasfee issue to legacy contracts
        // 0x7a56e1c57c7475ccf742a1832b028f0456652f97 fix beacon proxy
        //
        //ToDo: Implement fetching proxy / implementation --> Fetch both, compile implementation, fetch transaction from proxy, handle deploy etc
        //ToDO: Remove gasestimate on slither
        val target = "0xb8ca40e2c5d77f0bc1aa88b2689dddb279f7a5eb"
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