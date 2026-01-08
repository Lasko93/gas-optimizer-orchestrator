package de.orchestrator.gas_optimizer_orchestrator.model.slither

/**
 * Complete report from a Slither analysis run.
 */
data class SlitherReport(
    val success: Boolean,
    val error: String? = null,
    val findings: List<SlitherFinding>
) {
    companion object {
        fun failure(error: String) = SlitherReport(
            success = false,
            error = error,
            findings = emptyList()
        )

        fun success(findings: List<SlitherFinding>) = SlitherReport(
            success = true,
            error = null,
            findings = findings
        )
    }

    val totalFindings: Int
        get() = findings.size

    val highImpactFindings: Int
        get() = countByImpact(Impact.HIGH)

    val mediumImpactFindings: Int
        get() = countByImpact(Impact.MEDIUM)

    val lowImpactFindings: Int
        get() = countByImpact(Impact.LOW)

    val informationalFindings: Int
        get() = countByImpact(Impact.INFORMATIONAL)

    val optimizationFindings: Int
        get() = countByImpact(Impact.OPTIMIZATION)

    private fun countByImpact(impact: String): Int =
        findings.count { it.impact == impact }


    val findingsByImpact: Map<String, List<SlitherFinding>>
        get() = findings.groupBy { it.impact }

    object Impact {
        const val HIGH = "High"
        const val MEDIUM = "Medium"
        const val LOW = "Low"
        const val INFORMATIONAL = "Informational"
        const val OPTIMIZATION = "Optimization"
    }
}