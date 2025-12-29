package de.orchestrator.gas_optimizer_orchestrator.model.slither

data class SlitherFinding(
    val check: String,
    val impact: String,
    val confidence: String,
    val description: String,
    val firstMarkdownElement: String? = null,
    val elements: List<SlitherElement> = emptyList()
) {

    companion object {
        fun getEstimatedSavings(check: String): GasSavingsEstimate? =
            GasOptimizationChecks.getEstimatedSavings(check)
    }

    val isGasRelated: Boolean
        get() = GasOptimizationChecks.isGasRelated(check) || impact == "Optimization"

    val estimatedSavings: GasSavingsEstimate?
        get() = GasOptimizationChecks.getEstimatedSavings(check)

    val shortDescription: String
        get() = description.lines().firstOrNull()?.take(100) ?: description.take(100)
}