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

    //TODO: Fix all errors with 0x3d9819210A31b4961b30EF54bE2aeD79B9c9Cd3B --> ProxyImplementations // 0x1111111254EEB25477B68fb85Ed929f73A960582 --> max fee per gas less than
    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0xc2EdaD668740f1aA35E4D8f227fB8E17dcA888Cd"

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