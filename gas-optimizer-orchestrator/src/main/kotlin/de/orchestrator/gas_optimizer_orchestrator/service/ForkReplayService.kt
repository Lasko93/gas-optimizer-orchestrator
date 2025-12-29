package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome
import org.springframework.stereotype.Service

@Service
class ForkReplayService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService
) {

    fun replayOnForkAtPreviousBlock(
        interaction: ExecutableInteraction,
        beforeSend: (() -> Unit)? = null
    ): ReplayOutcome {
        val bn = interaction.blockNumber.toLongOrNull()
            ?: return ReplayOutcome(null, "Invalid blockNumber: ${interaction.blockNumber}")

        val forkBlock = bn - 1
        if (forkBlock < 0) {
            return ReplayOutcome(null, "Invalid fork block: blockNumber=$bn (cannot fork at -1)")
        }

        return try {
            val receipt = anvilManager.withAnvilFork(forkBlock,interaction.tx.timeStamp) {
                beforeSend?.invoke()
                anvilInteractionService.sendInteraction(interaction)
            }
            ReplayOutcome(receipt, null)
        } catch (e: Exception) {
            ReplayOutcome(null, "Fork $forkBlock failed: ${e.message}")
        }
    }
}