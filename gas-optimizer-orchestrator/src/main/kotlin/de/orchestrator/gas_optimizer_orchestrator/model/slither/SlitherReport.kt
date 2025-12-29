package de.orchestrator.gas_optimizer_orchestrator.model.slither


data class SlitherReport(
    val success: Boolean,
    val error: String? = null,
    val findings: List<SlitherFinding>
) {
    val gasOptimizationFindings: List<SlitherFinding>
        get() = findings.filter { it.isGasRelated }

    val totalFindings: Int get() = findings.size

    val highImpactFindings: Int get() = findings.count { it.impact == "High" }
    val mediumImpactFindings: Int get() = findings.count { it.impact == "Medium" }
    val lowImpactFindings: Int get() = findings.count { it.impact == "Low" }
    val informationalFindings: Int get() = findings.count { it.impact == "Informational" }
    val optimizationFindings: Int get() = findings.count { it.impact == "Optimization" }

    fun estimateTotalSavings(expectedCallsPerFunction: Long = 100): GasSavingsSummary {
        var totalPerCall = 0L
        var totalPerDeployment = 0L

        gasOptimizationFindings.forEach { finding ->
            finding.estimatedSavings?.let { est ->
                totalPerCall += est.perCall
                totalPerDeployment += est.perDeployment
            }
        }

        return GasSavingsSummary(
            estimatedDeploymentSavings = totalPerDeployment,
            estimatedPerCallSavings = totalPerCall,
            estimatedTotalSavings = totalPerDeployment + (totalPerCall * expectedCallsPerFunction),
            expectedCalls = expectedCallsPerFunction
        )
    }
}