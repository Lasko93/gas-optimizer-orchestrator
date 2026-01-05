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

        val target = "0x1f9840a85d5aF5bf1D1762F925BDADdC4201F984"

        val creationInfo = etherScanService.getContractCreationInfo(target)
        val creationTransaction = etherScanService.getTransactionByHash(creationInfo.txHash)
        val transactions = etherScanService.getTransactionsForAddress(target)
        val abiJson = etherScanService.getContractAbi(target)
        val srcMeta = etherScanService.getContractSourceCode(target, chainId = "1")

        println(creationInfo)
        // 1. Slither Analysis
        println("=== Running Slither Analysis ===")
        val slitherReport = slitherOrchestrator.analyzeGasOptimizations(srcMeta)

        // 2. Baseline Compilation
        println("=== Running Baseline Compilation ===")
        val baseline = initialRunOrchestrator.runInitial(
            transactions = transactions,
            srcMeta = srcMeta,
            abiJson = abiJson,
            creationTransaction = creationTransaction,
        )

        // 3. Optimized Compilations
        println("=== Running Optimized Compilations ===")
        val optimizedResults = solcOptimizerRunOrchestrator.runSolcOptimizerRuns(
            transactions = transactions,
            srcMeta = srcMeta,
            abiJson = abiJson,
            creationTransaction = creationTransaction,
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