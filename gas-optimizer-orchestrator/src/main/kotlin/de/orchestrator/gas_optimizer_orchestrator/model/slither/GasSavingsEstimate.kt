package de.orchestrator.gas_optimizer_orchestrator.model.slither

data class GasSavingsEstimate(
    val perCall: Long,
    val perDeployment: Long,
    val description: String
) {
    fun totalSavings(expectedCalls: Long): Long =
        perDeployment + (perCall * expectedCalls)
}

data class GasSavingsSummary(
    val estimatedDeploymentSavings: Long,
    val estimatedPerCallSavings: Long,
    val estimatedTotalSavings: Long,
    val expectedCalls: Long
)