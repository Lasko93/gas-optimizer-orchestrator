package de.orchestrator.gas_optimizer_orchestrator.model.slither


object GasOptimizationChecks {

    private val GAS_RELATED_CHECKS = setOf(
        "constable-states",
        "external-function",
        "costly-loop",
        "cache-array-length",
        "immutable-states",
        "dead-code",
        "unused-state"
    )


    //ToDo: How much gas can be saved estimated --> Fill in
    private val ESTIMATED_SAVINGS = mapOf(
        "constable-states" to GasSavingsEstimate(
            perCall = 2100L,
            perDeployment = 20000L,
            description = "Converts storage read to inline constant"
        ),
        "external-function" to GasSavingsEstimate(
            perCall = 200L,
            perDeployment = 0L,
            description = "Avoids copying calldata to memory"
        ),
        "immutable-states" to GasSavingsEstimate(
            perCall = 2100L,
            perDeployment = 15000L,
            description = "Replaces storage read with code-embedded value"
        ),
        "cache-array-length" to GasSavingsEstimate(
            perCall = 100L,
            perDeployment = 0L,
            description = "Caches array.length in local variable"
        ),
        "dead-code" to GasSavingsEstimate(
            perCall = 0L,
            perDeployment = 500L,
            description = "Removes unused code from bytecode"
        ),
        "unused-state" to GasSavingsEstimate(
            perCall = 0L,
            perDeployment = 20000L,
            description = "Removes unused storage slot"
        ),
        "costly-loop" to GasSavingsEstimate(
            perCall = 5000L,
            perDeployment = 0L,
            description = "Moves storage operations outside loop"
        )
    )

    fun isGasRelated(check: String): Boolean = check in GAS_RELATED_CHECKS

    fun getEstimatedSavings(check: String): GasSavingsEstimate? = ESTIMATED_SAVINGS[check]
}