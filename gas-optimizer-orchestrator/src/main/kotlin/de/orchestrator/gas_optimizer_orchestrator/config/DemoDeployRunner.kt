package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.externalApi.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.InitialRunOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.IrRunOrchestrator
import de.orchestrator.gas_optimizer_orchestrator.utils.GasMetricsUtil.formatPercent
import de.orchestrator.gas_optimizer_orchestrator.utils.GasMetricsUtil.percentageChange
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration
class DemoDeployConfig(
    private val etherScanService: EtherScanService,
    private val initialRunOrchestrator: InitialRunOrchestrator,
    private val irRunOrchestrator: IrRunOrchestrator
) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0xC9b7818FcEEC153554897718c288954FB376DaA3"

        val transactions = etherScanService.getTransactionsForAddress(target)
        val abiJson = etherScanService.getContractAbi(target)
        val srcMeta = etherScanService.getContractSourceCode(target, chainId = "1")

        val result = initialRunOrchestrator.runInitial(
            transactions = transactions,
            srcMeta = srcMeta,
            abiJson = abiJson
        )
        val optimizedResults = irRunOrchestrator.runIrRuns(transactions, srcMeta, abiJson)


        println("=== Initial Run Result ===")
        println("Contract: ${result.contractName}")
        println("Target address: ${result.contractAddress}")
        println("Solc: ${result.compilerInfo.solcVersion}")
        println("Deployment gas (no-fork local deploy): ${result.gasProfile.deploymentGasUsed}")
        println("Creation bytecode size: ${result.creationBytecodeSize} bytes")
        println("Runtime bytecode size: ${result.runtimeBytecodeSize} bytes")
        println("Replayed calls: ${result.gasProfile.functionCalls.size}")

        result.gasProfile.functionCalls.forEach {
            println(
                "Call ${it.functionSignature} -> gasUsed=${it.gasUsed}, ok=${it.succeeded}, tx=${it.txHash}, revert=${it.revertReason}"
            )
        }

        println()
        println("=== Optimized IR Run Results ===")
        println("Contract: ${srcMeta.contractName}")
        println("Target address: ${srcMeta.address}")
        println("Runs tested: ${optimizedResults.keys.sorted().joinToString(", ")}")
        println()

        optimizedResults.toSortedMap().forEach { (runs, r) ->
            println("=== IR Optimized Result (optimize-runs=$runs) ===")
            println("Contract: ${r.contractName}")
            println("Target address: ${r.contractAddress}")
            println("Solc: ${r.compilerInfo.solcVersion}")
            println("Deployment gas (no-fork local deploy): ${r.gasProfile.deploymentGasUsed}")
            println("Creation bytecode size: ${r.creationBytecodeSize} bytes")
            println("Runtime bytecode size: ${r.runtimeBytecodeSize} bytes")
            println("Replayed calls: ${r.gasProfile.functionCalls.size}")

            r.gasProfile.functionCalls.forEach {
                println(
                    "Call ${it.functionSignature} -> gasUsed=${it.gasUsed}, ok=${it.succeeded}, tx=${it.txHash}, revert=${it.revertReason}"
                )
            }
        }

        println()
        println("=== Delta Summary (IR vs Initial) ===")

        val baselineDeploy = result.gasProfile.deploymentGasUsed
        val baselineCreationSize = result.creationBytecodeSize
        val baselineRuntimeSize = result.runtimeBytecodeSize
        val baselineBySig = result.gasProfile.functionCalls.associateBy { it.functionSignature }

        fun d(x: Long) = if (x >= 0) "+$x" else x.toString()
        fun d(x: Int) = if (x >= 0) "+$x" else x.toString()

        optimizedResults.toSortedMap().forEach { (runs, opt) ->
            println()
            println("--- optimize-runs=$runs ---")

            // Deployment gas
            val depDelta = opt.gasProfile.deploymentGasUsed - baselineDeploy
            val depPct = percentageChange(baselineDeploy, opt.gasProfile.deploymentGasUsed)
            println("Deployment: ${opt.gasProfile.deploymentGasUsed} (delta ${d(depDelta)}, ${formatPercent(depPct)})")

            // Bytecode sizes
            val creationDelta = opt.creationBytecodeSize - baselineCreationSize
            val runtimeDelta = opt.runtimeBytecodeSize - baselineRuntimeSize
            val creationPct = percentageChange(baselineCreationSize.toLong(), opt.creationBytecodeSize.toLong())
            val runtimePct = percentageChange(baselineRuntimeSize.toLong(), opt.runtimeBytecodeSize.toLong())

            println("Creation bytecode: ${opt.creationBytecodeSize} bytes (delta ${d(creationDelta)}, ${formatPercent(creationPct)})")
            println("Runtime bytecode: ${opt.runtimeBytecodeSize} bytes (delta ${d(runtimeDelta)}, ${formatPercent(runtimePct)})")

            // Function calls
            opt.gasProfile.functionCalls.forEach { call ->
                val base = baselineBySig[call.functionSignature]
                val baseGas = base?.gasUsed ?: 0L
                val delta = call.gasUsed - baseGas
                val pct = percentageChange(baseGas, call.gasUsed)

                println("Call ${call.functionSignature}: ${call.gasUsed} (delta ${d(delta)}, ${formatPercent(pct)})")
            }
        }
    }
}