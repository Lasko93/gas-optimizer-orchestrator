package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import org.springframework.stereotype.Service
import org.web3j.protocol.core.methods.response.TransactionReceipt

@Service
class ForkReplayService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService
) {
    data class ReplayOutcome(
        val receipt: TransactionReceipt?,
        val errorMessage: String?
    )

    fun replayOnForkWithFallback(interaction: ExecutableInteraction): ReplayOutcome {
        val bn = interaction.blockNumber.toLongOrNull()
            ?: return ReplayOutcome(null, "Invalid blockNumber: ${interaction.blockNumber}")

        val candidates = listOf(bn - 1, bn)
            .distinct()
            .filter { it >= 0 }

        var lastError: String? = null

        for (forkBlock in candidates) {
            try {
                val receipt = anvilManager.withAnvilFork(forkBlock) {
                    anvilInteractionService.sendInteraction(interaction)
                }
                return ReplayOutcome(receipt, null)
            } catch (e: Exception) {
                lastError = "Fork $forkBlock failed: ${e.message}"
            }
        }

        return ReplayOutcome(null, lastError ?: "Replay failed")
    }
}
