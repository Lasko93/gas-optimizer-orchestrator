package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome
import org.springframework.stereotype.Service
import org.web3j.protocol.core.methods.response.TransactionReceipt

@Service
class ForkReplayService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService
) {


    /**
     * Replays with fork(block-1) fallback to fork(block).
     * beforeSend is executed inside the fork context, right before sending the tx
     * (perfect for anvil_setCode).
     */
    fun replayOnForkWithFallback(
        interaction: ExecutableInteraction,
        beforeSend: (() -> Unit)? = null
    ): ReplayOutcome {
        val bn = interaction.blockNumber.toLongOrNull()
            ?: return ReplayOutcome(null, "Invalid blockNumber: ${interaction.blockNumber}")

        val candidates = listOf(bn - 1, bn)
            .distinct()
            .filter { it >= 0 }

        var lastError: String? = null

        for (forkBlock in candidates) {
            try {
                val receipt = anvilManager.withAnvilFork(forkBlock) {
                    beforeSend?.invoke()
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
