package de.orchestrator.gas_optimizer_orchestrator.orchestrators

/**
 * Constants used across gas optimization orchestrators.
 */
object OrchestratorConstants {

    // Default chain configuration
    const val DEFAULT_CHAIN_ID = 1L
    const val DEFAULT_RPC_URL = "http://localhost:8545"

    // Compilation defaults
    val DEFAULT_OPTIMIZER_RUNS = listOf(1, 200, 10_000)
    const val DEFAULT_OUT_DIR = "out"
}