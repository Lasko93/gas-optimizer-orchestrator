package de.orchestrator.gas_optimizer_orchestrator.service.anvil

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.ReplayOutcome
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ResolvedContractInfo
import de.orchestrator.gas_optimizer_orchestrator.service.ProxyUpdateService
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.protocol.core.methods.request.Transaction
import java.math.BigInteger

/**
 * Service for replaying transactions on Anvil forks with custom contract bytecode.
 *
 * Supports both direct contracts and proxy contracts:
 * - Direct: Replaces runtime bytecode at the contract address
 * - Proxy: Deploys new implementation and updates the proxy's implementation slot
 */
@Service
class ForkReplayService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService,
    private val web3j: Web3j,
    private val proxyUpdateService: ProxyUpdateService
) {
    private val logger = LoggerFactory.getLogger(ForkReplayService::class.java)

    companion object {
        private val ONE_GWEI = BigInteger("1000000000")
        private val DEFAULT_FUNDING_AMOUNT = BigInteger.TEN.pow(20)
        private const val SUCCESS_STATUS = "0x1"
        private const val ERROR_SELECTOR = "0x08c379a0"
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Replays an interaction with custom bytecode on a fork.
     *
     * Process:
     * 1. Fork at (interaction.blockNumber - 1)
     * 2. Deploy optimized bytecode to get initialized runtime
     * 3. For proxies: update implementation slot
     *    For direct: replace runtime bytecode at address
     * 4. Execute interaction and measure gas
     *
     * @param interaction The interaction to replay
     * @param creationBytecode The custom creation bytecode
     * @param constructorArgsHex Constructor arguments as hex
     * @param resolved The resolved contract info
     * @return ReplayOutcome with gas usage or failure reason
     */
    fun replayWithCustomContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): ReplayOutcome {
        val forkBlock = validateAndGetForkBlock(interaction)
            ?: return ReplayOutcome.Failed("Invalid blockNumber: ${interaction.blockNumber}")

        if (forkBlock < 0) {
            return ReplayOutcome.Failed("Invalid fork block: cannot fork at block -1")
        }

        return executeOnFork(forkBlock, interaction, creationBytecode, constructorArgsHex, resolved)
    }

    // ============================================================
    // Fork Execution
    // ============================================================

    private fun executeOnFork(
        forkBlock: Long,
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): ReplayOutcome {
        return try {
            anvilManager.withAnvilFork(forkBlock, interaction.tx.timeStamp) {
                if (resolved.isProxy) {
                    replayProxyContract(interaction, creationBytecode, constructorArgsHex, resolved)
                } else {
                    replayDirectContract(interaction, creationBytecode, constructorArgsHex, resolved)
                }
            }
        } catch (e: Exception) {
            logger.error("Fork execution failed at block {}: {}", forkBlock, e.message)
            ReplayOutcome.Failed("Fork $forkBlock failed: ${e.message}")
        }
    }

    // ============================================================
    // Direct Contract Replay
    // ============================================================

    private fun replayDirectContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): ReplayOutcome {
        logger.debug("Replaying direct contract at {}", resolved.implementationAddress)

        // Deploy to get initialized runtime bytecode
        val runtimeBytecode = deployAndExtractRuntime(creationBytecode, constructorArgsHex, resolved)

        // Replace bytecode at the target address
        anvilManager.replaceRuntimeBytecode(resolved.implementationAddress, runtimeBytecode)

        // Setup sender and execute
        setupSender(interaction.fromAddress)
        return executeAndMeasure(interaction)
    }

    // ============================================================
    // Proxy Contract Replay
    // ============================================================

    private fun replayProxyContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): ReplayOutcome {
        val proxyAddress = resolved.proxyAddress
            ?: return ReplayOutcome.Failed("Proxy address is null for proxy contract")

        logger.info("Replaying proxy contract: {} → {}", proxyAddress, resolved.implementationAddress)

        // Deploy new implementation
        val newImplAddress = deployNewImplementation(creationBytecode, constructorArgsHex, resolved)
        logDeployedImplementation(newImplAddress)

        // Update proxy to point to new implementation
        val updateResult = proxyUpdateService.updateProxyImplementation(proxyAddress, newImplAddress)
        if (updateResult.isFailure) {
            return ReplayOutcome.Failed("Failed to update proxy: ${updateResult.exceptionOrNull()?.message}")
        }

        logger.info("Updated proxy {} → {}", proxyAddress, newImplAddress)

        // Setup sender and execute
        setupSender(interaction.fromAddress)
        return executeAndMeasure(interaction)
    }

    // ============================================================
    // Deployment Helpers
    // ============================================================

    /**
     * Deploys creation bytecode and returns the deployed runtime bytecode.
     */
    private fun deployAndExtractRuntime(
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): String {
        BytecodeUtil.validateBytecode(creationBytecode)

        val deployBytecode = BytecodeUtil.appendConstructorArgs(creationBytecode, constructorArgsHex)
        val creationTx = resolved.creationTransaction

        setupSender(creationTx.from)

        val receipt = anvilInteractionService.sendRawTransaction(
            from = creationTx.from,
            to = null,
            value = creationTx.value,
            gasLimit = anvilInteractionService.gasLimit(),
            gasPrice = creationTx.gasPrice,
            data = deployBytecode
        )

        return extractRuntimeBytecode(receipt.contractAddress)
    }

    /**
     * Deploys new implementation and returns its address.
     */
    private fun deployNewImplementation(
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): String {
        BytecodeUtil.validateBytecode(creationBytecode)

        val deployBytecode = BytecodeUtil.appendConstructorArgs(creationBytecode, constructorArgsHex)
        val creationTx = resolved.creationTransaction
        val gasPrice = adjustGasPriceForBaseFee(creationTx.gasPrice)

        setupSender(creationTx.from)

        val receipt = anvilInteractionService.sendRawTransaction(
            from = creationTx.from,
            to = null,
            value = BigInteger.ZERO,
            gasLimit = anvilInteractionService.gasLimit(),
            gasPrice = gasPrice,
            data = deployBytecode
        )

        return receipt.contractAddress
            ?: throw IllegalStateException("No contract address from deployment receipt")
    }

    private fun extractRuntimeBytecode(contractAddress: String?): String {
        if (contractAddress == null) {
            throw IllegalStateException("No contract address from deployment receipt")
        }

        val code = web3j.ethGetCode(contractAddress, DefaultBlockParameterName.LATEST).send().code

        if (code.isNullOrBlank() || code == "0x") {
            throw IllegalStateException("Failed to get deployed runtime bytecode for $contractAddress")
        }

        return code
    }

    private fun logDeployedImplementation(address: String) {
        val code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().code
        val sizeBytes = (code.length - 2) / 2
        logger.debug("New implementation at {} ({} bytes)", address, sizeBytes)
    }

    // ============================================================
    // Transaction Execution
    // ============================================================

    private fun setupSender(address: String) {
        anvilManager.impersonateAccount(address)
        anvilManager.setBalance(address, DEFAULT_FUNDING_AMOUNT)
    }

    private fun executeAndMeasure(interaction: ExecutableInteraction): ReplayOutcome {
        val adjustedInteraction = adjustInteractionGasPrice(interaction)

        // Simulate first to get revert reason if any
        val revertReason = simulateCall(adjustedInteraction)

        val receipt = anvilInteractionService.sendInteraction(adjustedInteraction)

        return if (receipt.status == SUCCESS_STATUS) {
            ReplayOutcome.Success(receipt, receipt.gasUsed)
        } else {
            ReplayOutcome.Reverted(receipt, receipt.gasUsed, revertReason)
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

            val result = web3j.ethCall(tx, DefaultBlockParameterName.LATEST).send()

            if (result.isReverted) {
                decodeRevertReason(result.revertReason) ?: "Reverted without reason"
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Call simulation failed: {}", e.message)
            "Simulation failed: ${e.message}"
        }
    }

    // ============================================================
    // Gas Price Adjustment (EIP-1559)
    // ============================================================

    private fun adjustInteractionGasPrice(interaction: ExecutableInteraction): ExecutableInteraction {
        val originalGasPrice = interaction.tx.gasPrice.toBigIntegerOrNull() ?: BigInteger.ZERO
        val adjustedGasPrice = adjustGasPriceForBaseFee(originalGasPrice)

        if (adjustedGasPrice == originalGasPrice) {
            return interaction
        }

        return interaction.copy(
            tx = interaction.tx.copy(gasPrice = adjustedGasPrice.toString())
        )
    }

    private fun adjustGasPriceForBaseFee(originalGasPrice: BigInteger): BigInteger {
        val baseFee = getBlockBaseFee()

        if (baseFee == BigInteger.ZERO) {
            return originalGasPrice
        }

        val minGasPrice = baseFee + ONE_GWEI
        return maxOf(originalGasPrice, minGasPrice)
    }

    private fun getBlockBaseFee(): BigInteger {
        return try {
            web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false)
                .send()
                .block
                .baseFeePerGas ?: BigInteger.ZERO
        } catch (e: Exception) {
            logger.trace("Could not get base fee: {}", e.message)
            BigInteger.ZERO
        }
    }

    // ============================================================
    // Utility Methods
    // ============================================================

    private fun validateAndGetForkBlock(interaction: ExecutableInteraction): Long? {
        val blockNumber = interaction.blockNumber.toLongOrNull() ?: return null
        return blockNumber - 1
    }

    private fun decodeRevertReason(revertReason: String?): String? {
        if (revertReason.isNullOrBlank()) return null

        return try {
            if (revertReason.startsWith(ERROR_SELECTOR)) {
                decodeErrorString(revertReason.removePrefix(ERROR_SELECTOR))
            } else {
                revertReason
            }
        } catch (e: Exception) {
            logger.trace("Failed to decode revert reason: {}", e.message)
            revertReason
        }
    }

    /**
     * Decodes an ABI-encoded Error(string) message.
     */
    private fun decodeErrorString(hex: String): String? {
        if (hex.length < 128) return null

        val lengthHex = hex.substring(64, 128)
        val length = lengthHex.toBigInteger(16).toInt()
        val messageHex = hex.substring(128, 128 + length * 2)

        return String(
            messageHex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        )
    }
}