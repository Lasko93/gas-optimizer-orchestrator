package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.api.DemoRunResult
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.effectiveSourceMeta
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.InitialRunOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SlitherOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.SolcOptimizerOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.service.etherscan.ContractResolverService
import de.orchestrator.gas_optimizer_orchestrator.utils.reporting.ConsoleReporter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class DemoDeployRunnerService(
    private val contractResolverService: ContractResolverService,
    private val initialRunOrchestrator: InitialRunOrchestrator,
    private val solcOptimizerOrchestrator: SolcOptimizerOrchestrator,
    private val slitherOrchestrator: SlitherOrchestrator
) {
    private val logger = LoggerFactory.getLogger(DemoDeployRunnerService::class.java)

    @Volatile
    private var running: Boolean = false

    /**
     * Runs the demo, but rejects new requests while one run is in progress.
     */
    fun runDemoExclusive(address: String): DemoRunResult {
        synchronized(this) {
            if (running) {
                return DemoRunResult(ok = false, message = "A demo run is already in progress. Try again later.")
            }
            running = true
        }

        try {
            runDemo(address)
            return DemoRunResult(ok = true, message = "Demo run finished successfully.")
        } catch (e: Exception) {
            logger.error("Demo run failed for address={}", address, e)
            return DemoRunResult(ok = false, message = "Demo run failed: ${e.message ?: e::class.simpleName}")
        } finally {
            running = false
        }
    }

    private fun runDemo(address: String) {
        val resolution = contractResolverService.resolve(address)
        val sourceMeta = resolution.effectiveSourceMeta

        logger.info("=== Running Slither Analysis ===")
        val slitherReport = slitherOrchestrator.analyzeGasOptimizations(sourceMeta)

        logger.info("=== Running Baseline Compilation ===")
        val baseline = initialRunOrchestrator.run(resolution)

        logger.info("=== Running Optimized Compilations ===")
        val optimizedResults = solcOptimizerOrchestrator.run(resolution)

        ConsoleReporter.printFullReport(
            slitherReport = slitherReport,
            baseline = baseline,
            optimizedResults = optimizedResults,
            srcMeta = sourceMeta
        )
    }
}