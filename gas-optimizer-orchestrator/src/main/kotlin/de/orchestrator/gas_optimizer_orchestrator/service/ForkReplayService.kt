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
     * Replay interaction using bytecode replacement at original address.
     *
     * Flow:
     * 1. Fork at interaction block - 1
     * 2. Deploy optimized contract (to extract runtime bytecode with immutables initialized)
     * 3. Replace bytecode at ORIGINAL address with deployed runtime bytecode
     * 4. Send transaction to ORIGINAL address
     *
     * This preserves:
     * - External approvals (tokens approved for original address)
     * - Immutable values (from fresh deployment)
     * - Mainnet state (from fork)
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
                // 1. Deploy optimized contract to get runtime bytecode with immutables initialized
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

                val tempContractAddress = deployReceipt.contractAddress
                    ?: return@withAnvilFork ReplayOutcome.Failed("No contract address from deployment")

                // 2. Get the deployed runtime bytecode (has immutables initialized)
                val deployedRuntimeBytecode = web3j.ethGetCode(tempContractAddress, org.web3j.protocol.core.DefaultBlockParameterName.LATEST)
                    .send()
                    .code

                if (deployedRuntimeBytecode.isNullOrBlank() || deployedRuntimeBytecode == "0x") {
                    return@withAnvilFork ReplayOutcome.Failed("Failed to get deployed runtime bytecode")
                }

                // 3. Replace bytecode at ORIGINAL address with the deployed runtime bytecode
                anvilManager.replaceRuntimeBytecode(
                    address = originalContractAddress,
                    runtimeBytecode = deployedRuntimeBytecode
                )

                // 4. Simulate first (using original address)
                val revertReason = simulateCall(interaction)

                // 5. Send transaction to ORIGINAL address
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