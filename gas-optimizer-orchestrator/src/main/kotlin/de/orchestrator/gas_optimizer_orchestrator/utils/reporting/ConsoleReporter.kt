package de.orchestrator.gas_optimizer_orchestrator.utils.reporting

import de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.model.GasTrackingResults
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherReport
import de.orchestrator.gas_optimizer_orchestrator.utils.gas.GasAnalysisUtil

/**
 * Generates console reports for gas optimization analysis.
 */
object ConsoleReporter {

    private const val WIDTH = ReportFormatter.DEFAULT_LINE_WIDTH

    // ============================================================
    // Full Report
    // ============================================================

    /**
     * Prints a complete gas optimization analysis report.
     */
    fun printFullReport(
        slitherReport: SlitherReport?,
        baseline: GasTrackingResults,
        optimizedResults: Map<Int, GasTrackingResults>,
        srcMeta: ContractSourceCodeResult
    ) {
        printReportHeader(baseline.contractName)

        slitherReport?.let { printSlitherSection(it) }
        printBaselineSection(baseline)
        printOptimizedSection(srcMeta, optimizedResults)
        printDeltaSection(baseline, optimizedResults)
        printFailureSection(baseline, optimizedResults)
    }

    // ============================================================
    // Report Header
    // ============================================================

    private fun printReportHeader(contractName: String) {
        println()
        println(ReportFormatter.headerBox(
            listOf("GAS OPTIMIZATION ANALYSIS REPORT", contractName),
            WIDTH
        ))
    }

    // ============================================================
    // Slither Section
    // ============================================================

    private fun printSlitherSection(report: SlitherReport) {
        if (!report.success) {
            println("Slither analysis failed: ${report.error}")
            return
        }

        println()
        println(ReportFormatter.headerBox("SLITHER ANALYSIS", WIDTH))
        println()
        println("Summary:")
        println(ReportFormatter.labelValue("Total findings:", report.totalFindings))
        println(ReportFormatter.labelValue("High impact:", report.highImpactFindings))
        println(ReportFormatter.labelValue("Medium impact:", report.mediumImpactFindings))
        println(ReportFormatter.labelValue("Low impact:", report.lowImpactFindings))
        println(ReportFormatter.labelValue("Informational:", report.informationalFindings))
        println(ReportFormatter.labelValue("Optimization:", report.optimizationFindings))
        println()

        if (report.findings.isNotEmpty()) {
            println("Findings by Category:")
            println(ReportFormatter.separator(WIDTH))

            report.findingsByImpact.forEach { (impact, findings) ->
                println()
                println("[$impact] (${findings.size} finding(s))")

                findings.forEach { finding ->
                    val location = finding.elements.firstOrNull()?.let { el ->
                        val lines = el.sourceMapping?.lines?.take(3)?.joinToString(",") ?: "?"
                        "${el.sourceMapping?.filename ?: ""}:$lines"
                    } ?: ""

                    println("  • [${finding.check}] ${finding.shortDescription}")
                    if (location.isNotEmpty()) {
                        println("    Location: $location")
                    }
                }
            }
        } else {
            println("No findings.")
        }

        println()
        println(ReportFormatter.separator(WIDTH, '═'))
    }

    // ============================================================
    // Baseline Section
    // ============================================================

    private fun printBaselineSection(result: GasTrackingResults) {
        println()
        println(ReportFormatter.headerBox("BASELINE COMPILATION (NO OPTIMIZATION)", WIDTH))
        println()
        println(ReportFormatter.labelValue("Contract:", result.contractName))
        println(ReportFormatter.labelValue("Address:", result.contractAddress))
        println(ReportFormatter.labelValue("Solc:", result.compilerInfo.solcVersion))
        println()
        println("Bytecode:")
        println(ReportFormatter.labelValue("Creation size:", "${result.creationBytecodeSize} bytes"))
        println(ReportFormatter.labelValue("Runtime size:", "${result.runtimeBytecodeSize} bytes"))
        println()
        println(ReportFormatter.labelValue("Deployment gas:", result.gasProfile.deploymentGasUsed))
        println()

        printFunctionCalls(result.gasProfile.functionCalls, "Function Calls")
    }

    // ============================================================
    // Optimized Results Section
    // ============================================================

    private fun printOptimizedSection(
        srcMeta: ContractSourceCodeResult,
        optimizedResults: Map<Int, GasTrackingResults>
    ) {
        println()
        println(ReportFormatter.headerBox("OPTIMIZED COMPILATION RESULTS", WIDTH))
        println()
        println(ReportFormatter.labelValue("Contract:", srcMeta.contractName))
        println(ReportFormatter.labelValue("Address:", srcMeta.address))
        println(ReportFormatter.labelValue("Runs tested:", optimizedResults.keys.sorted().joinToString(", ")))
        println()

        optimizedResults.toSortedMap().forEach { (runs, result) ->
            println(ReportFormatter.sectionHeader("optimize-runs=$runs", WIDTH))
            println(ReportFormatter.labelValue("Solc:", result.compilerInfo.solcVersion))
            println(ReportFormatter.labelValue("Creation size:", "${result.creationBytecodeSize} bytes"))
            println(ReportFormatter.labelValue("Runtime size:", "${result.runtimeBytecodeSize} bytes"))
            println(ReportFormatter.labelValue("Deployment gas:", result.gasProfile.deploymentGasUsed))
            println()

            if (result.gasProfile.functionCalls.isNotEmpty()) {
                println("  Function Calls:")
                result.gasProfile.functionCalls.forEach { call ->
                    val status = ReportFormatter.statusIcon(call.succeeded)
                    print("    $status ${call.functionSignature}: ${call.gasUsed} gas")
                    if (!call.succeeded && !call.revertReason.isNullOrBlank()) {
                        println()
                        println("      ↳ ${ReportFormatter.truncate(call.revertReason)}")
                    } else {
                        println()
                    }
                }
            }
            println()
        }
    }

    // ============================================================
    // Delta Summary Section
    // ============================================================

    private fun printDeltaSection(
        baseline: GasTrackingResults,
        optimizedResults: Map<Int, GasTrackingResults>
    ) {
        println()
        println(ReportFormatter.headerBox("OPTIMIZATION DELTA SUMMARY", WIDTH))
        println()

        val baselineDeploy = baseline.gasProfile.deploymentGasUsed
        val baselineCreationSize = baseline.creationBytecodeSize
        val baselineRuntimeSize = baseline.runtimeBytecodeSize
        val baselineBySig = baseline.gasProfile.functionCalls.associateBy { it.functionSignature }

        println("Baseline (no optimization):")
        println(ReportFormatter.labelValue("Deployment:", "$baselineDeploy gas"))
        println(ReportFormatter.labelValue("Creation size:", "$baselineCreationSize bytes"))
        println(ReportFormatter.labelValue("Runtime size:", "$baselineRuntimeSize bytes"))
        println()

        optimizedResults.toSortedMap().forEach { (runs, opt) ->
            println(ReportFormatter.sectionHeader("optimize-runs=$runs", WIDTH))

            // Deployment gas
            printDeltaLine(
                "Deployment:",
                opt.gasProfile.deploymentGasUsed,
                baselineDeploy,
                "gas"
            )

            // Bytecode sizes
            printDeltaLine(
                "Creation:",
                opt.creationBytecodeSize.toLong(),
                baselineCreationSize.toLong(),
                "bytes"
            )
            printDeltaLine(
                "Runtime:",
                opt.runtimeBytecodeSize.toLong(),
                baselineRuntimeSize.toLong(),
                "bytes"
            )

            // Function calls
            if (opt.gasProfile.functionCalls.isNotEmpty()) {
                println()
                println("  Function Calls:")
                opt.gasProfile.functionCalls.forEach { call ->
                    val base = baselineBySig[call.functionSignature]
                    val baseGas = base?.gasUsed ?: 0L
                    val delta = call.gasUsed - baseGas
                    val pct = GasAnalysisUtil.percentageChange(baseGas, call.gasUsed)
                    val icon = ReportFormatter.changeIcon(delta)
                    val status = ReportFormatter.statusIcon(call.succeeded)

                    println("    $icon $status ${call.functionSignature}")
                    println("       ${call.gasUsed} gas (${ReportFormatter.formatDelta(delta)}, ${GasAnalysisUtil.formatPercent(pct)})")

                    if (!call.succeeded && !call.revertReason.isNullOrBlank()) {
                        println("       ↳ ${ReportFormatter.truncate(call.revertReason)}")
                    }
                }
            }
            println()
        }
    }

    private fun printDeltaLine(label: String, optimized: Long, baseline: Long, unit: String) {
        val delta = optimized - baseline
        val pct = GasAnalysisUtil.percentageChange(baseline, optimized)
        val icon = ReportFormatter.changeIcon(delta)
        println("  $icon ${label.padEnd(14)} $optimized $unit (${ReportFormatter.formatDelta(delta)}, ${GasAnalysisUtil.formatPercent(pct)})")
    }

    // ============================================================
    // Failure Summary Section
    // ============================================================

    private fun printFailureSection(
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
        println(ReportFormatter.headerBox("FAILURE SUMMARY", WIDTH))
        println()

        failures.groupBy { it.second }.forEach { (sig, failureList) ->
            println("  $sig")
            failureList.forEach { (runs, _, reason) ->
                val runsLabel = if (runs == -1) "baseline" else "runs=$runs"
                println("    ✗ [$runsLabel] ${ReportFormatter.truncate(reason ?: "Unknown error")}")
            }
            println()
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private fun printFunctionCalls(calls: List<FunctionGasUsed>, title: String) {
        if (calls.isEmpty()) return

        val succeeded = calls.count { it.succeeded }
        val failed = calls.size - succeeded

        println("$title ($succeeded succeeded, $failed failed):")
        println(ReportFormatter.separator(WIDTH))

        calls.forEach { call ->
            val status = ReportFormatter.statusIcon(call.succeeded)
            println("  $status ${call.functionSignature}")
            println("    Gas: ${call.gasUsed}")
            if (!call.succeeded && !call.revertReason.isNullOrBlank()) {
                println("    Revert: ${call.revertReason}")
            }
        }
        println()
    }
}