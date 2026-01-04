package de.orchestrator.gas_optimizer_orchestrator.utils

import de.orchestrator.gas_optimizer_orchestrator.model.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.GasTrackingResults
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherFinding
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherReport
import de.orchestrator.gas_optimizer_orchestrator.utils.GasMetricsUtil.formatPercent
import de.orchestrator.gas_optimizer_orchestrator.utils.GasMetricsUtil.percentageChange

object PrintUtil {

    private const val LINE_WIDTH = 66

    private fun d(x: Long) = if (x >= 0) "+$x" else x.toString()
    private fun d(x: Int) = if (x >= 0) "+$x" else x.toString()

    // ════════════════════════════════════════════════════════════════
    // Slither Report
    // ════════════════════════════════════════════════════════════════

    fun printSlitherReport(report: SlitherReport) {
        if (!report.success) {
            println("Slither analysis failed: ${report.error}")
            return
        }

        println()
        println("╔${"═".repeat(LINE_WIDTH)}╗")
        println("║${centerText("SLITHER GAS ANALYSIS", LINE_WIDTH)}║")
        println("╚${"═".repeat(LINE_WIDTH)}╝")
        println()
        println("Summary:")
        println("  Total findings: ${report.totalFindings}")
        println("  Optimization:   ${report.optimizationFindings}")
        println("  High impact:    ${report.highImpactFindings}")
        println("  Medium impact:  ${report.mediumImpactFindings}")
        println("  Low impact:     ${report.lowImpactFindings}")
        println("  Informational:  ${report.informationalFindings}")
        println()

        val savings = report.estimateTotalSavings(expectedCallsPerFunction = 100)
        println("Estimated Gas Savings (if all optimizations applied):")
        println("  Deployment:        ~${savings.estimatedDeploymentSavings} gas")
        println("  Per call:          ~${savings.estimatedPerCallSavings} gas")
        println()

        if (report.gasOptimizationFindings.isNotEmpty()) {
            println("Gas Optimization Suggestions:")
            println("─".repeat(LINE_WIDTH))

            report.gasOptimizationFindings
                .groupBy { it.check }
                .forEach { (check, findings) ->
                    val estimate = SlitherFinding.getEstimatedSavings(check)
                    val savingsStr = estimate?.let {
                        " [~${it.perCall} gas/call, ~${it.perDeployment} gas deploy]"
                    } ?: ""

                    println()
                    println("[$check] (${findings.size} finding(s))$savingsStr")
                    if (estimate != null) {
                        println("  💡 ${estimate.description}")
                    }

                    findings.take(5).forEach { finding ->
                        val location = finding.elements.firstOrNull()?.let { el ->
                            val lines = el.sourceMapping?.lines?.take(3)?.joinToString(",") ?: "?"
                            "${el.sourceMapping?.filename ?: ""}:$lines"
                        } ?: ""

                        println("  • ${finding.shortDescription}")
                        if (location.isNotEmpty()) {
                            println("    Location: $location")
                        }
                    }

                    if (findings.size > 5) {
                        println("  ... and ${findings.size - 5} more")
                    }
                }
        } else {
            println("No gas optimization suggestions found.")
        }

        println()
        println("═".repeat(LINE_WIDTH))
    }

    // ════════════════════════════════════════════════════════════════
    // Initial Run Result
    // ════════════════════════════════════════════════════════════════

    fun printInitialRunResult(result: GasTrackingResults) {
        println()
        println("╔${"═".repeat(LINE_WIDTH)}╗")
        println("║${centerText("BASELINE COMPILATION (NO OPTIMIZATION)", LINE_WIDTH)}║")
        println("╚${"═".repeat(LINE_WIDTH)}╝")
        println()
        println("Contract:          ${result.contractName}")
        println("Address:           ${result.contractAddress}")
        println("Solc:              ${result.compilerInfo.solcVersion}")
        println()
        println("Bytecode:")
        println("  Creation size:   ${result.creationBytecodeSize} bytes")
        println("  Runtime size:    ${result.runtimeBytecodeSize} bytes")
        println()
        println("Deployment gas:    ${result.gasProfile.deploymentGasUsed}")
        println()

        printFunctionCallsDetailed(result.gasProfile.functionCalls, "Function Calls")
    }

    // ════════════════════════════════════════════════════════════════
    // Optimized Results Overview
    // ════════════════════════════════════════════════════════════════

    fun printOptimizedResultsOverview(
        srcMeta: ContractSourceCodeResult,
        optimizedResults: Map<Int, GasTrackingResults>
    ) {
        println()
        println("╔${"═".repeat(LINE_WIDTH)}╗")
        println("║${centerText("OPTIMIZED COMPILATION RESULTS", LINE_WIDTH)}║")
        println("╚${"═".repeat(LINE_WIDTH)}╝")
        println()
        println("Contract:          ${srcMeta.contractName}")
        println("Address:           ${srcMeta.address}")
        println("Runs tested:       ${optimizedResults.keys.sorted().joinToString(", ")}")
        println()

        optimizedResults.toSortedMap().forEach { (runs, r) ->
            println("┌${"─".repeat(LINE_WIDTH)}┐")
            println("│${centerText("optimize-runs=$runs", LINE_WIDTH)}│")
            println("└${"─".repeat(LINE_WIDTH)}┘")
            println("  Solc:            ${r.compilerInfo.solcVersion}")
            println("  Creation size:   ${r.creationBytecodeSize} bytes")
            println("  Runtime size:    ${r.runtimeBytecodeSize} bytes")
            println("  Deployment gas:  ${r.gasProfile.deploymentGasUsed}")
            println()

            if (r.gasProfile.functionCalls.isNotEmpty()) {
                println("  Function Calls:")
                r.gasProfile.functionCalls.forEach {
                    val status = if (it.succeeded) "✓" else "✗"
                    print("    $status ${it.functionSignature}: ${it.gasUsed} gas")
                    if (!it.succeeded && !it.revertReason.isNullOrBlank()) {
                        println()
                        println("      ↳ ${truncateReason(it.revertReason)}")
                    } else {
                        println()
                    }
                }
            }
            println()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Delta Summary
    // ════════════════════════════════════════════════════════════════

    fun printDeltaSummary(
        baseline: GasTrackingResults,
        optimizedResults: Map<Int, GasTrackingResults>
    ) {
        println()
        println("╔${"═".repeat(LINE_WIDTH)}╗")
        println("║${centerText("OPTIMIZATION DELTA SUMMARY", LINE_WIDTH)}║")
        println("╚${"═".repeat(LINE_WIDTH)}╝")
        println()

        val baselineDeploy = baseline.gasProfile.deploymentGasUsed
        val baselineCreationSize = baseline.creationBytecodeSize
        val baselineRuntimeSize = baseline.runtimeBytecodeSize
        val baselineBySig = baseline.gasProfile.functionCalls.associateBy { it.functionSignature }

        println("Baseline (no optimization):")
        println("  Deployment:      ${baselineDeploy} gas")
        println("  Creation size:   ${baselineCreationSize} bytes")
        println("  Runtime size:    ${baselineRuntimeSize} bytes")
        println()

        optimizedResults.toSortedMap().forEach { (runs, opt) ->
            println("┌${"─".repeat(LINE_WIDTH)}┐")
            println("│${centerText("optimize-runs=$runs", LINE_WIDTH)}│")
            println("└${"─".repeat(LINE_WIDTH)}┘")

            // Deployment gas
            val depDelta = opt.gasProfile.deploymentGasUsed - baselineDeploy
            val depPct = percentageChange(baselineDeploy, opt.gasProfile.deploymentGasUsed)
            val depIcon = if (depDelta < 0) "📉" else if (depDelta > 0) "📈" else "➖"
            println("  $depIcon Deployment:    ${opt.gasProfile.deploymentGasUsed} gas (${d(depDelta)}, ${formatPercent(depPct)})")

            // Bytecode sizes
            val creationDelta = opt.creationBytecodeSize - baselineCreationSize
            val runtimeDelta = opt.runtimeBytecodeSize - baselineRuntimeSize
            val creationPct = percentageChange(baselineCreationSize.toLong(), opt.creationBytecodeSize.toLong())
            val runtimePct = percentageChange(baselineRuntimeSize.toLong(), opt.runtimeBytecodeSize.toLong())

            val creationIcon = if (creationDelta < 0) "📉" else if (creationDelta > 0) "📈" else "➖"
            val runtimeIcon = if (runtimeDelta < 0) "📉" else if (runtimeDelta > 0) "📈" else "➖"

            println("  $creationIcon Creation:      ${opt.creationBytecodeSize} bytes (${d(creationDelta)}, ${formatPercent(creationPct)})")
            println("  $runtimeIcon Runtime:       ${opt.runtimeBytecodeSize} bytes (${d(runtimeDelta)}, ${formatPercent(runtimePct)})")

            // Function calls
            if (opt.gasProfile.functionCalls.isNotEmpty()) {
                println()
                println("  Function Calls:")
                opt.gasProfile.functionCalls.forEach { call ->
                    val base = baselineBySig[call.functionSignature]
                    val baseGas = base?.gasUsed ?: 0L
                    val delta = call.gasUsed - baseGas
                    val pct = percentageChange(baseGas, call.gasUsed)
                    val icon = if (delta < 0) "📉" else if (delta > 0) "📈" else "➖"

                    val statusIcon = if (call.succeeded) "✓" else "✗"
                    println("    $icon $statusIcon ${call.functionSignature}")
                    println("       ${call.gasUsed} gas (${d(delta)}, ${formatPercent(pct)})")

                    if (!call.succeeded && !call.revertReason.isNullOrBlank()) {
                        println("       ↳ ${truncateReason(call.revertReason)}")
                    }
                }
            }
            println()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Failure Summary (new section)
    // ════════════════════════════════════════════════════════════════

    fun printFailureSummary(
        baseline: GasTrackingResults,
        optimizedResults: Map<Int, GasTrackingResults>
    ) {
        val allResults = mapOf(-1 to baseline) + optimizedResults
        val failures = allResults.flatMap { (runs, result) ->
            result.gasProfile.functionCalls
                .filter { !it.succeeded }
                .map { Triple(runs, it.functionSignature, it.revertReason) }
        }

        if (failures.isEmpty()) return

        println()
        println("╔${"═".repeat(LINE_WIDTH)}╗")
        println("║${centerText("FAILURE SUMMARY", LINE_WIDTH)}║")
        println("╚${"═".repeat(LINE_WIDTH)}╝")
        println()

        // Group by function signature
        failures.groupBy { it.second }.forEach { (sig, failureList) ->
            println("  $sig")
            failureList.forEach { (runs, _, reason) ->
                val runsLabel = if (runs == -1) "baseline" else "runs=$runs"
                println("    ✗ [$runsLabel] ${truncateReason(reason ?: "Unknown error")}")
            }
            println()
        }
    }

    // ════════════════════════════════════════════════════════════════
    // Full Report (combines all sections)
    // ════════════════════════════════════════════════════════════════

    fun printFullReport(
        slitherReport: SlitherReport?,
        baseline: GasTrackingResults,
        optimizedResults: Map<Int, GasTrackingResults>,
        srcMeta: ContractSourceCodeResult
    ) {
        // Header
        println()
        println("╔${"═".repeat(LINE_WIDTH)}╗")
        println("║${centerText("GAS OPTIMIZATION ANALYSIS REPORT", LINE_WIDTH)}║")
        println("║${centerText(baseline.contractName, LINE_WIDTH)}║")
        println("╚${"═".repeat(LINE_WIDTH)}╝")

        // Slither (if available)
        if (slitherReport != null) {
            printSlitherReport(slitherReport)
        }

        // Baseline
        printInitialRunResult(baseline)

        // Optimized results
        printOptimizedResultsOverview(srcMeta, optimizedResults)

        // Delta summary
        printDeltaSummary(baseline, optimizedResults)

        // Failure summary
        printFailureSummary(baseline, optimizedResults)
    }

    // ════════════════════════════════════════════════════════════════
    // Helpers
    // ════════════════════════════════════════════════════════════════

    private fun printFunctionCallsDetailed(
        calls: List<de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed>,
        title: String
    ) {
        if (calls.isEmpty()) return

        val succeeded = calls.count { it.succeeded }
        val failed = calls.size - succeeded

        println("$title ($succeeded succeeded, $failed failed):")
        println("─".repeat(LINE_WIDTH))

        calls.forEach {
            val status = if (it.succeeded) "✓" else "✗"
            println("  $status ${it.functionSignature}")
            println("    Gas: ${it.gasUsed}")
            if (!it.succeeded && !it.revertReason.isNullOrBlank()) {
                println("    Revert: ${it.revertReason}")
            }
        }
        println()
    }

    private fun truncateReason(reason: String, maxLength: Int = 50): String {
        return if (reason.length > maxLength) {
            reason.take(maxLength - 3) + "..."
        } else {
            reason
        }
    }

    private fun centerText(text: String, width: Int): String {
        val padding = (width - text.length) / 2
        return " ".repeat(padding.coerceAtLeast(0)) + text + " ".repeat((width - text.length - padding).coerceAtLeast(0))
    }
}