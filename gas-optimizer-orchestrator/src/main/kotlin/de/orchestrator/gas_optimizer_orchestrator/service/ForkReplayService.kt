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
     * Enhanced to handle proxy contracts by detecting proxy type and updating implementation appropriately.
     *
     * Flow:
     * 1. Detect proxy type (EIP-1967, Compound, or regular contract)
     * 2. For proxies: Update implementation slot with optimized contract
     * 3. For regular contracts: Replace bytecode at contract address
     * 4. Execute transaction and measure gas usage
     * 5. Restore original state if needed
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
                // 1. Detect proxy type first
                val proxyInfo = proxyDetectionService.detectProxyType(originalContractAddress)
                println("Detected proxy type: ${proxyInfo.proxyType} for $originalContractAddress")

                // 2. Handle based on proxy type
                when (proxyInfo.proxyType) {
                    ProxyType.EIP1967 -> handleEIP1967Proxy(
                        interaction, creationBytecode, constructorArgsHex,
                        originalContractAddress, creationTx, proxyInfo
                    )
                    ProxyType.BEACON -> handleBeaconProxy(
                        interaction, creationBytecode, constructorArgsHex,
                        originalContractAddress, creationTx, proxyInfo
                    )
                    ProxyType.COMPOUND -> handleCompoundProxy(
                        interaction, creationBytecode, constructorArgsHex,
                        originalContractAddress, creationTx
                    )
                    ProxyType.NONE -> handleRegularContract(
                        interaction, creationBytecode, constructorArgsHex,
                        originalContractAddress, creationTx
                    )
                    else -> ReplayOutcome.Failed("Unsupported proxy type: ${proxyInfo.proxyType}")
                }
            }
        } catch (e: Exception) {
            ReplayOutcome.Failed("Fork $forkBlock failed: ${e.message}")
        }
    }

    /**
     * Handle EIP-1967 proxy by updating implementation slot
     */
    private fun handleEIP1967Proxy(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction,
        proxyInfo: ProxyInfo
    ): ReplayOutcome {
        // Save current implementation address
        val currentImpl = proxyInfo.implementationAddress
            ?: return ReplayOutcome.Failed("No implementation address found for EIP1967 proxy")

        println("Handling EIP-1967 proxy. Current implementation: $currentImpl")

        // Deploy new optimized implementation
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

        val newImplAddress = deployReceipt.contractAddress
            ?: return ReplayOutcome.Failed("No contract address from implementation deployment")

        println("Deployed new implementation at: $newImplAddress")

        // Update implementation slot (not replace proxy bytecode)
        val implSlotValue = "0x" + "0".repeat(24) + newImplAddress.substring(2).lowercase()
        anvilManager.setStorageAt(
            address = originalContractAddress,
            storageSlot = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc",
            value = implSlotValue
        )

        println("Updated implementation slot for proxy $originalContractAddress")

        // Reset reentrancy guard on implementation
        try {
            anvilManager.resetReentrancyGuard(newImplAddress, slotIndex = 0)
        } catch (e: Exception) {
            println("Warning: Could not reset reentrancy guard for new implementation: ${e.message}")
        }

        // Execute transaction (will use new implementation via delegatecall)
        return executeAndMeasure(interaction, "EIP-1967 Proxy")
    }

    /**
     * Handle Beacon proxy pattern
     */
    private fun handleBeaconProxy(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction,
        proxyInfo: ProxyInfo
    ): ReplayOutcome {
        val beaconAddress = proxyInfo.beaconAddress
            ?: return ReplayOutcome.Failed("No beacon address found for beacon proxy")

        println("Handling Beacon proxy. Beacon: $beaconAddress")

        // Deploy new optimized implementation
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

        val newImplAddress = deployReceipt.contractAddress
            ?: return ReplayOutcome.Failed("No contract address from implementation deployment")

        println("Deployed new implementation at: $newImplAddress")

        // Update beacon's implementation slot
        val implSlotValue = "0x" + "0".repeat(24) + newImplAddress.substring(2).lowercase()
        anvilManager.setStorageAt(
            address = beaconAddress,
            storageSlot = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc",
            value = implSlotValue
        )

        println("Updated implementation in beacon contract for proxy $originalContractAddress")

        // Execute transaction
        return executeAndMeasure(interaction, "Beacon Proxy")
    }

    /**
     * Handle Compound-style proxy by updating implementation in slot 0
     */
    private fun handleCompoundProxy(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction
    ): ReplayOutcome {
        println("Handling Compound-style proxy")

        // Compound-style proxies store implementation at slot 2 (admin at slot 0)
        val implementationSlot = "0x0000000000000000000000000000000000000000000000000000000000000002"
        val currentImpl = anvilManager.getStorageAt(originalContractAddress, implementationSlot)
        println("Current implementation in slot 2: $currentImpl")

        // Deploy new implementation
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

        val newImplAddress = deployReceipt.contractAddress
            ?: return ReplayOutcome.Failed("No contract address from implementation deployment")

        println("Deployed new implementation at: $newImplAddress")

        // Update implementation address in proxy storage
        val implAddressPadded = "0x" + "0".repeat(24) + newImplAddress.substring(2).lowercase()
        anvilManager.setStorageAt(originalContractAddress, implementationSlot, implAddressPadded)

        println("Updated implementation slot for Compound proxy $originalContractAddress")

        // Execute transaction (delegatecall to new implementation)
        return executeAndMeasure(interaction, "Compound Proxy")
    }

    /**
     * Handle regular contracts with normal bytecode replacement
     */
    private fun handleRegularContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        originalContractAddress: String,
        creationTx: FullTransaction
    ): ReplayOutcome {
        println("Handling regular contract (not a proxy)")

        // Original workflow for non-proxy contracts
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
            ?: return ReplayOutcome.Failed("No contract address from deployment")

        // Get the deployed runtime bytecode (has immutables initialized)
        val deployedRuntimeBytecode = web3j.ethGetCode(tempContractAddress, DefaultBlockParameterName.LATEST)
            .send()
            .code

        if (deployedRuntimeBytecode.isNullOrBlank() || deployedRuntimeBytecode == "0x") {
            return ReplayOutcome.Failed("Failed to get deployed runtime bytecode")
        }

        // Replace bytecode at ORIGINAL address with the deployed runtime bytecode
        anvilManager.replaceRuntimeBytecode(
            address = originalContractAddress,
            runtimeBytecode = deployedRuntimeBytecode
        )

        println("Replaced bytecode for regular contract $originalContractAddress")

        // Reset reentrancy guard after bytecode replacement
        try {
            anvilManager.resetReentrancyGuard(originalContractAddress, slotIndex = 0)
        } catch (e: Exception) {
            println("Warning: Could not reset reentrancy guard for $originalContractAddress: ${e.message}")
        }

        return executeAndMeasure(interaction, "Regular Contract")
    }

    /**
     * Common execution logic for all proxy types
     */
    private fun executeAndMeasure(interaction: ExecutableInteraction, proxyType: String): ReplayOutcome {
        // Simulate first
        val revertReason = simulateCall(interaction)

        // Execute transaction
        val receipt = anvilInteractionService.sendInteraction(interaction)
        val gasUsed = receipt.gasUsed

        println("Executed $proxyType interaction. Gas used: $gasUsed, Status: ${
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