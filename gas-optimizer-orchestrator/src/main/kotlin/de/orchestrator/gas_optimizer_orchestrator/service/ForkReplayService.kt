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
     * Replay interaction using bytecode replacement at original address.
     * Enhanced to handle proxy contracts by delegating to ProxyDetectionService.
     *
     * Flow:
     * 1. Detect if handling proxy or regular contract
     * 2. For proxies: Deploy optimized implementation and update proxy via ProxyDetectionService
     * 3. For regular contracts: Replace bytecode directly
     * 4. Execute transaction and measure gas usage
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
                println("📋 Starting replay for contract at $originalContractAddress")

                // 1. Detect proxy type first
                val proxyInfo = proxyDetectionService.detectProxyType(originalContractAddress)
                println("🔍 Detected proxy type: ${proxyInfo.proxyType} for $originalContractAddress")

                // 2. Handle based on contract type
                when (proxyInfo.proxyType) {
                    ProxyType.NONE -> handleRegularContract(
                        interaction, creationBytecode, constructorArgsHex,
                        originalContractAddress, creationTx
                    )
                    else -> handleProxyContract(
                        proxyInfo, interaction, creationBytecode, constructorArgsHex,
                        originalContractAddress, creationTx
                    )
                }
            }
        } catch (e: Exception) {
            ReplayOutcome.Failed("Fork $forkBlock failed: ${e.message}")
        }
    }

    /**
     * Handle regular (non-proxy) contracts using direct bytecode replacement.
     * This is the traditional approach for regular contracts.
     */
    private fun handleRegularContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction
    ): ReplayOutcome {
        println("📝 Handling regular contract")

        // Deploy the new optimized contract to get runtime bytecode
        BytecodeUtil.validateBytecode(creationBytecode)
        val deployBytecode = BytecodeUtil.appendConstructorArgs(creationBytecode, constructorArgsHex)

        // Set up deployment
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
            ?: return ReplayOutcome.Failed("No contract address from deployment")

        // Get the runtime bytecode with immutables initialized
        val deployedRuntimeBytecode = web3j.ethGetCode(tempContractAddress, DefaultBlockParameterName.LATEST)
            .send()
            .code

        if (deployedRuntimeBytecode.isNullOrBlank() || deployedRuntimeBytecode == "0x") {
            return ReplayOutcome.Failed("Failed to get deployed runtime bytecode")
        }

        // Replace bytecode at the contract address
        anvilManager.replaceRuntimeBytecode(
            address = originalContractAddress,
            runtimeBytecode = deployedRuntimeBytecode
        )

        // Reset any reentrancy guards
        proxyDetectionService.resetReentrancyGuard(originalContractAddress)

        println("✅ Updated regular contract at $originalContractAddress")
        return executeAndMeasure(interaction, "Regular Contract")
    }

    /**
     * Handle proxy contracts by deploying optimized implementation and updating proxy.
     * All proxy-specific logic is delegated to ProxyDetectionService.
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
                executeAndMeasure(interaction, "${proxyInfo.proxyType} Proxy")
            }
            else -> {
                val exception = result.exceptionOrNull()
                ReplayOutcome.Failed("Failed to update proxy: ${exception?.message ?: "Unknown error"}")
            }
        }
    }

    /**
     * Common execution logic for both proxy and regular contracts.
     * Simulates, executes, and measures gas usage.
     */
    private fun executeAndMeasure(interaction: ExecutableInteraction, contractType: String): ReplayOutcome {
        // Simulate first
        val revertReason = simulateCall(interaction)

        // Execute transaction
        val receipt = anvilInteractionService.sendInteraction(interaction)
        val gasUsed = receipt.gasUsed

        println("🚀 Executed $contractType interaction - Gas: $gasUsed, Status: ${
            if (receipt.status == "0x1") "SUCCESS" else "REVERTED"
        }")

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