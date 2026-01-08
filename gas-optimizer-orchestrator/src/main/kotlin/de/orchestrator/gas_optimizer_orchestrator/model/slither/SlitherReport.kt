package de.orchestrator.gas_optimizer_orchestrator.model.slither

data class SlitherReport(
    val success: Boolean,
    val error: String? = null,
    val findings: List<SlitherFinding>
) {
    val totalFindings: Int get() = findings.size

    val highImpactFindings: Int get() = findings.count { it.impact == "High" }
    val mediumImpactFindings: Int get() = findings.count { it.impact == "Medium" }
    val lowImpactFindings: Int get() = findings.count { it.impact == "Low" }
    val informationalFindings: Int get() = findings.count { it.impact == "Informational" }
    val optimizationFindings: Int get() = findings.count { it.impact == "Optimization" }

    /** All findings grouped by their impact/category */
    val findingsByImpact: Map<String, List<SlitherFinding>>
        get() = findings.groupBy { it.impact }
}