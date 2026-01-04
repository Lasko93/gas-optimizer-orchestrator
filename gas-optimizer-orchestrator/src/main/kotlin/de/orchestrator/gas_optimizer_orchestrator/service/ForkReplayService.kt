package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome
import org.springframework.stereotype.Service
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction

@Service
class ForkReplayService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService,
    private val web3j: Web3j
) {

    fun replayOnForkAtPreviousBlock(
        interaction: ExecutableInteraction,
        beforeSend: (() -> Unit)? = null
    ): ReplayOutcome {
        val bn = interaction.blockNumber.toLongOrNull()
            ?: return ReplayOutcome.Failed("Invalid blockNumber: ${interaction.blockNumber}")

        val forkBlock = bn - 1
        if (forkBlock < 0) {
            return ReplayOutcome.Failed("Invalid fork block: blockNumber=$bn (cannot fork at -1)")
        }

        return try {
            anvilManager.withAnvilFork(forkBlock, interaction.tx.timeStamp) {
                beforeSend?.invoke()

                // Simulate first with eth_call to capture revert reason
                val revertReason = simulateCall(interaction)

                // Actually send the transaction
                val receipt = anvilInteractionService.sendInteraction(interaction)
                val gasUsed = receipt.gasUsed

                if (receipt.status == "0x1") {
                    ReplayOutcome.Success(receipt, gasUsed)
                } else {
                    ReplayOutcome.Reverted(
                        receipt = receipt,
                        gasUsed = gasUsed,
                        revertReason = revertReason
                    )
                }
            }
        } catch (e: Exception) {
            ReplayOutcome.Failed("Fork $forkBlock failed: ${e.message}")
        }
    }

    private fun simulateCall(interaction: ExecutableInteraction): String? {
        return try {
            val tx = Transaction.createFunctionCallTransaction(
                interaction.fromAddress,
                null,
                interaction.tx.gasPrice.toBigIntegerOrNull(),
                interaction.tx.gas.toBigIntegerOrNull(),
                interaction.contractAddress,
                interaction.value,
                interaction.encoded()
            )

            val callResult = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send()
            if (callResult.isReverted) {
                decodeRevertReason(callResult.revertReason) ?: "Reverted without reason"
            } else {
                null
            }
        } catch (e: Exception) {
            "Simulation failed: ${e.message}"
        }
    }

    private fun decodeRevertReason(revertReason: String?): String? {
        if (revertReason.isNullOrBlank()) return null

        return try {
            if (revertReason.startsWith("0x08c379a0")) {
                // Standard Error(string) selector
                val hex = revertReason.removePrefix("0x08c379a0")
                if (hex.length >= 128) {
                    val lengthHex = hex.substring(64, 128)
                    val length = lengthHex.toBigInteger(16).toInt()
                    val messageHex = hex.substring(128, 128 + length * 2)
                    String(messageHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                } else {
                    revertReason
                }
            } else {
                revertReason
            }
        } catch (e: Exception) {
            revertReason
        }
    }
}