package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.externalApi.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.InitialRunOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SlitherOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SolcOptimizerOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.utils.PrintUtil
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
class DemoDeployConfig(
    private val etherScanService: EtherScanService,
    private val initialRunOrchestrator: InitialRunOrchestrator,
    private val solcOptimizerRunOrchestrator: SolcOptimizerOrchestrator,
    private val slitherOrchestrator: SlitherOrchestrator
) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0xdAe1ACC21eD8E26BEB311Edeb70e1ae5e27e8A0b"

        val transactions = etherScanService.getTransactionsForAddress(target)
        val abiJson = etherScanService.getContractAbi(target)
        val srcMeta = etherScanService.getContractSourceCode(target, chainId = "1")

        // 1. Slither Analysis
        println("=== Running Slither Analysis ===")
        val slitherReport = slitherOrchestrator.analyzeGasOptimizations(srcMeta)

        // 2. Baseline Compilation
        println("=== Running Baseline Compilation ===")
        val baseline = initialRunOrchestrator.runInitial(
            transactions = transactions,
            srcMeta = srcMeta,
            abiJson = abiJson
        )

        // 3. Optimized Compilations
        println("=== Running Optimized Compilations ===")
        val optimizedResults = solcOptimizerRunOrchestrator.runSolcOptimizerRuns(
            transactions = transactions,
            srcMeta = srcMeta,
            abiJson = abiJson
        )

        // 4. Print Full Report
        PrintUtil.printFullReport(
            slitherReport = slitherReport,
            baseline = baseline,
            optimizedResults = optimizedResults,
            srcMeta = srcMeta
        )
    }
}