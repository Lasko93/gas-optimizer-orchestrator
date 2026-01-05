package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.FullTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyType
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import org.springframework.stereotype.Service
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import java.math.BigInteger

@Service
class ForkReplayService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService,
    private val web3j: Web3j,
    private val proxyDetectionService: ProxyDetectionService
) {

    fun replayWithCustomContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction
    ): ReplayOutcome {
        val bn = interaction.blockNumber.toLongOrNull()
            ?: return ReplayOutcome.Failed("Invalid blockNumber: ${interaction.blockNumber}")

        val forkBlock = bn - 1
        if (forkBlock < 0) {
            return ReplayOutcome.Failed("Invalid fork block: blockNumber=$bn (cannot fork at -1)")
        }

        return try {
            anvilManager.withAnvilFork(forkBlock, interaction.tx.timeStamp) {
                val proxyInfo = proxyDetectionService.detectProxyType(originalContractAddress)

                when (proxyInfo.proxyType) {
                    ProxyType.NONE -> handleRegularContract(
                        interaction = interaction,
                        originalContractAddress = originalContractAddress,
                    )

                    else -> handleProxyContract(
                        proxyInfo = proxyInfo,
                        interaction = interaction,
                    )
                }
            }
        } catch (e: Exception) {
            ReplayOutcome.Failed("Fork $forkBlock failed: ${e.message}")
        }
    }

    private fun handleRegularContract(
        interaction: ExecutableInteraction,
        originalContractAddress: String,
    ): ReplayOutcome {
        val originalInteraction = interaction.copy(contractAddress = originalContractAddress)

        // Impersonate original sender + fund it
        anvilManager.impersonateAccount(originalInteraction.fromAddress)
        anvilManager.setBalance(originalInteraction.fromAddress, BigInteger.TEN.pow(20))

        return executeAndMeasure(originalInteraction)
    }


    private fun handleProxyContract(
        proxyInfo: de.orchestrator.gas_optimizer_orchestrator.model.ProxyInfo,
        interaction: ExecutableInteraction,
    ): ReplayOutcome {
        // Ensure we target the proxy address (original on-chain address)
        val proxyAddress = proxyInfo.proxyAddress ?: interaction.contractAddress
        val proxyInteraction = interaction.copy(contractAddress = proxyAddress)

        // Impersonate original sender + fund it
        anvilManager.impersonateAccount(proxyInteraction.fromAddress)
        anvilManager.setBalance(proxyInteraction.fromAddress, BigInteger.TEN.pow(20))

        return executeAndMeasure(proxyInteraction)
    }

    /**
     * Execute transaction and measure gas.
     */
    private fun executeAndMeasure(interaction: ExecutableInteraction): ReplayOutcome {
        val revertReason = simulateCall(interaction)

        val receipt = anvilInteractionService.sendInteraction(interaction)
        val gasUsed = receipt.gasUsed

        return if (receipt.status == "0x1") {
            ReplayOutcome.Success(receipt, gasUsed)
        } else {
            ReplayOutcome.Reverted(
                receipt = receipt,
                gasUsed = gasUsed,
                revertReason = revertReason
            )
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
            // Error(string) selector 0x08c379a0
            if (revertReason.startsWith("0x08c379a0")) {
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
        } catch (_: Exception) {
            revertReason
        }
    }
}