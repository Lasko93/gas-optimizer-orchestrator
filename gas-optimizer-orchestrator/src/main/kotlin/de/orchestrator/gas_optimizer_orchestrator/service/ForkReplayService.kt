package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.FullTransaction
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
    private val web3j: Web3j
) {

    /**
     * Replay interaction on a fork with a freshly deployed custom contract.
     *
     * Flow:
     * 1. Fork at interaction block - 1
     * 2. Deploy optimized bytecode
     * 3. Copy storage from original mainnet contract
     * 4. Update interaction to target new deployed address
     * 5. Send transaction
     *
     * This ensures immutables are initialized AND mainnet state is preserved.
     */
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
                // 1. Deploy optimized contract
                BytecodeUtil.validateBytecode(creationBytecode)
                val deployBytecode = BytecodeUtil.appendConstructorArgs(creationBytecode, constructorArgsHex)

                anvilManager.impersonateAccount(creationTx.from)
                anvilManager.setBalance(creationTx.from, BigInteger.TEN.pow(20))

                val deployReceipt = anvilInteractionService.sendRawTransaction(
                    from = creationTx.from,
                    to = null,
                    value = creationTx.value,
                    gasLimit = anvilInteractionService.gasLimit(),
                    gasPrice = creationTx.gasPrice,
                    data = deployBytecode
                )

                val newContractAddress = deployReceipt.contractAddress
                    ?: return@withAnvilFork ReplayOutcome.Failed("No contract address from deployment")

                // 2. Copy storage from original mainnet contract
                anvilManager.copyStorage(fromAddress = originalContractAddress, toAddress = newContractAddress)

                // 3. Update interaction to target the new deployed address
                val updatedInteraction = interaction.copy(contractAddress = newContractAddress)

                // 4. Simulate first
                val revertReason = simulateCall(updatedInteraction)

                // 5. Send the transaction
                val receipt = anvilInteractionService.sendInteraction(updatedInteraction)
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