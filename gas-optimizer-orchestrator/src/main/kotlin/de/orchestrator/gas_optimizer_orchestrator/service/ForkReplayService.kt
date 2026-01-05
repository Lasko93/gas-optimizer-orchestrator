package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.FullTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyInfo
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

    /**
     * New logic (as requested):
     * - Fork at (interaction.blockNumber - 1)
     * - Detect proxy vs normal
     * - Deploy optimized creation bytecode once (so immutables are initialized)
     * - Read runtime bytecode from the deployed temp contract
     * - anvil_setCode at the ORIGINAL address we will call (normal: originalContractAddress, proxy: proxyAddress)
     * - Execute the interaction against that ORIGINAL address and measure gas
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
                val proxyInfo = proxyDetectionService.detectProxyType(originalContractAddress)

                when (proxyInfo.proxyType) {
                    ProxyType.NONE -> handleRegularContract(
                        interaction = interaction,
                        creationBytecode = creationBytecode,
                        constructorArgsHex = constructorArgsHex,
                        originalContractAddress = originalContractAddress,
                        creationTx = creationTx
                    )

                    else -> handleProxyContract(
                        proxyInfo = proxyInfo,
                        interaction = interaction,
                        creationBytecode = creationBytecode,
                        constructorArgsHex = constructorArgsHex,
                        creationTx = creationTx,
                        originalContractAddress = originalContractAddress
                    )
                }
            }
        } catch (e: Exception) {
            ReplayOutcome.Failed("Fork $forkBlock failed: ${e.message}")
        }
    }

    private fun handleRegularContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction
    ): ReplayOutcome {
        val originalInteraction = interaction.copy(contractAddress = originalContractAddress)

        // 1) Deploy optimized contract to get runtime bytecode with immutables initialized
        val deployedRuntimeBytecode = deployAndGetInitializedRuntime(
            creationBytecode = creationBytecode,
            constructorArgsHex = constructorArgsHex,
            creationTx = creationTx
        )

        // 2) Replace runtime bytecode at ORIGINAL address
        anvilManager.replaceRuntimeBytecode(
            address = originalContractAddress,
            runtimeBytecode = deployedRuntimeBytecode
        )

        // 3) Impersonate sender + fund it (so tx can be mined)
        anvilManager.impersonateAccount(originalInteraction.fromAddress)
        anvilManager.setBalance(originalInteraction.fromAddress, BigInteger.TEN.pow(20))

        // 4) Execute + measure against ORIGINAL address
        return executeAndMeasure(originalInteraction)
    }


    /**
     * Handle proxy contracts by deploying optimized implementation and updating proxy.
     * All proxy-specific logic is delegated to ProxyDetectionService.
     *
     * (Kept exactly in spirit as you requested.)
     */
    private fun handleProxyContract(
        proxyInfo: ProxyInfo,
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction
    ): ReplayOutcome {
        println("🔗 Handling ${proxyInfo.proxyType} proxy")

        // Deploy new implementation and update proxy via ProxyDetectionService
        val result = proxyDetectionService.deployAndUpdateImplementation(
            proxyInfo = proxyInfo,
            creationBytecode = creationBytecode,
            constructorArgsHex = constructorArgsHex,
            deployerAddress = creationTx.from,
            deployerValue = "0x" + creationTx.value.toString(16),
            gasPrice = "0x" + creationTx.gasPrice.toString(16)
        )

        return when {
            result.isSuccess -> {
                println("✅ Updated ${proxyInfo.proxyType} proxy with new implementation at ${result.getOrNull()}")

                // Ensure sender can pay for gas on fork
                anvilManager.impersonateAccount(interaction.fromAddress)
                anvilManager.setBalance(interaction.fromAddress, BigInteger.TEN.pow(20))

                executeAndMeasure(interaction)
            }

            else -> {
                val exception = result.exceptionOrNull()
                ReplayOutcome.Failed("Failed to update proxy: ${exception?.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Deploys the given creation bytecode (with constructor args) so immutables are initialized,
     * then returns the deployed runtime bytecode via eth_getCode.
     */
    private fun deployAndGetInitializedRuntime(
        creationBytecode: String,
        constructorArgsHex: String,
        creationTx: FullTransaction
    ): String {
        BytecodeUtil.validateBytecode(creationBytecode)

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = creationBytecode,
            constructorArgsHex = constructorArgsHex
        )

        // deployer must exist + be funded on fork
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
            ?: throw IllegalStateException("No contract address from deployment receipt")

        val deployedRuntimeBytecode = web3j
            .ethGetCode(tempContractAddress, DefaultBlockParameterName.LATEST)
            .send()
            .code

        if (deployedRuntimeBytecode.isNullOrBlank() || deployedRuntimeBytecode == "0x") {
            throw IllegalStateException("Failed to get deployed runtime bytecode for $tempContractAddress")
        }

        return deployedRuntimeBytecode
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