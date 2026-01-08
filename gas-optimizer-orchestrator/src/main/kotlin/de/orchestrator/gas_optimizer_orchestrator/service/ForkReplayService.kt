package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.ResolvedContractInfo
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
    private val proxyUpdateService: ProxyUpdateService
) {
    companion object {
        private val ONE_GWEI = BigInteger("1000000000")
    }
    /**
     * Replay interaction with custom bytecode:
     * - Fork at (interaction.blockNumber - 1)
     * - Deploy optimized creation bytecode to get initialized runtime
     * - For proxies: update implementation slot to point to new implementation
     * - For direct contracts: replace runtime bytecode at original address
     * - Execute the interaction and measure gas
     */
    fun replayWithCustomContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): ReplayOutcome {
        val bn = interaction.blockNumber.toLongOrNull()
            ?: return ReplayOutcome.Failed("Invalid blockNumber: ${interaction.blockNumber}")

        val forkBlock = bn - 1
        if (forkBlock < 0) {
            return ReplayOutcome.Failed("Invalid fork block: blockNumber=$bn (cannot fork at -1)")
        }

        return try {
            anvilManager.withAnvilFork(forkBlock, interaction.tx.timeStamp) {
                if (resolved.isProxy) {
                    handleProxyContract(
                        interaction = interaction,
                        creationBytecode = creationBytecode,
                        constructorArgsHex = constructorArgsHex,
                        resolved = resolved
                    )
                } else {
                    handleRegularContract(
                        interaction = interaction,
                        creationBytecode = creationBytecode,
                        constructorArgsHex = constructorArgsHex,
                        resolved = resolved
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
        resolved: ResolvedContractInfo
    ): ReplayOutcome {

        // 1) Deploy optimized contract to get runtime bytecode with immutables initialized
        val deployedRuntimeBytecode = deployAndGetInitializedRuntime(
            creationBytecode = creationBytecode,
            constructorArgsHex = constructorArgsHex,
            resolved = resolved
        )

        // 2) Replace runtime bytecode at the implementation address
        anvilManager.replaceRuntimeBytecode(
            address = resolved.implementationAddress,
            runtimeBytecode = deployedRuntimeBytecode
        )

        // 3) Impersonate sender + fund it (so tx can be mined)
        anvilManager.impersonateAccount(interaction.fromAddress)
        anvilManager.setBalance(interaction.fromAddress, BigInteger.TEN.pow(20))

        // 4) Execute + measure
        return executeAndMeasure(interaction)
    }

    private fun handleProxyContract(
        interaction: ExecutableInteraction,
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): ReplayOutcome {
        val proxyAddress = resolved.proxyAddress
            ?: return ReplayOutcome.Failed("Proxy address is null for proxy contract")

        println("🔗 Handling proxy at $proxyAddress -> ${resolved.implementationAddress}")

        // 1) Deploy new implementation to get address
        val newImplAddress = deployNewImplementation(
            creationBytecode = creationBytecode,
            constructorArgsHex = constructorArgsHex,
            resolved = resolved
        )

        println("   📦 New implementation deployed at: $newImplAddress")
        val newImplCode = web3j.ethGetCode(newImplAddress, DefaultBlockParameterName.LATEST).send().code
        println("   📏 New implementation bytecode size: ${(newImplCode.length - 2) / 2} bytes")

        // 2) Update proxy to point to new implementation
        val updateResult = proxyUpdateService.updateProxyImplementation(
            proxyAddress = proxyAddress,
            newImplementationAddress = newImplAddress
        )


        if (updateResult.isFailure) {
            return ReplayOutcome.Failed("Failed to update proxy: ${updateResult.exceptionOrNull()?.message}")
        }

        println("✅ Updated proxy $proxyAddress -> $newImplAddress")

        // 3) Impersonate sender + fund it
        anvilManager.impersonateAccount(interaction.fromAddress)
        anvilManager.setBalance(interaction.fromAddress, BigInteger.TEN.pow(20))

        // 4) Execute against PROXY address (interaction.contractAddress should already be proxy)
        return executeAndMeasure(interaction)
    }

    /**
     * Deploys the given creation bytecode (with constructor args) so immutables are initialized,
     * then returns the deployed runtime bytecode via eth_getCode.
     */
    private fun deployAndGetInitializedRuntime(
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): String {
        BytecodeUtil.validateBytecode(creationBytecode)

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = creationBytecode,
            constructorArgsHex = constructorArgsHex
        )

        val creationTx = resolved.creationTransaction

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
     * Deploy new implementation and return its address.
     */
    private fun deployNewImplementation(
        creationBytecode: String,
        constructorArgsHex: String,
        resolved: ResolvedContractInfo
    ): String {
        BytecodeUtil.validateBytecode(creationBytecode)

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = creationBytecode,
            constructorArgsHex = constructorArgsHex
        )
        val creationTx = resolved.creationTransaction
        val adjustedGasPrice = getAdjustedGasPrice(creationTx.gasPrice)

        anvilManager.impersonateAccount(creationTx.from)
        anvilManager.setBalance(creationTx.from, BigInteger.TEN.pow(20))

        val deployReceipt = anvilInteractionService.sendRawTransaction(
            from = creationTx.from,
            to = null,
            value = BigInteger.ZERO, // Implementations typically deployed with 0 value
            gasLimit = anvilInteractionService.gasLimit(),
            gasPrice = adjustedGasPrice,
            data = deployBytecode
        )

        return deployReceipt.contractAddress
            ?: throw IllegalStateException("No contract address from deployment receipt")
    }

    /**
     * Execute transaction and measure gas.
     * Handles EIP-1559 by ensuring gas price is at least the block's base fee.
     */
    private fun executeAndMeasure(interaction: ExecutableInteraction): ReplayOutcome {
        // Adjust gas price for EIP-1559 (post-London fork)
        val adjustedInteraction = adjustInteractionGasPrice(interaction)

        val revertReason = simulateCall(adjustedInteraction)

        val receipt = anvilInteractionService.sendInteraction(adjustedInteraction)
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

    private fun getAdjustedGasPrice(originalGasPrice: BigInteger): BigInteger {
        val baseFee = getBlockBaseFee()
        if (baseFee == BigInteger.ZERO) {
            return originalGasPrice
        }

        val minGasPrice = baseFee.add(ONE_GWEI)
        return maxOf(originalGasPrice, minGasPrice)
    }

    /**
     * Adjusts gas price to be at least the current block's base fee.
     * Required for post-EIP-1559 (London fork) blocks.
     */
    private fun adjustInteractionGasPrice(interaction: ExecutableInteraction): ExecutableInteraction {
        val originalGasPrice = interaction.tx.gasPrice.toBigIntegerOrNull() ?: BigInteger.ZERO
        val adjustedGasPrice = getAdjustedGasPrice(originalGasPrice)

        if (adjustedGasPrice == originalGasPrice) {
            return interaction
        }

        return interaction.copy(
            tx = interaction.tx.copy(gasPrice = adjustedGasPrice.toString())
        )
    }


    /**
     * Gets the current block's base fee, or ZERO if not available (pre-London).
     */
    private fun getBlockBaseFee(): BigInteger {
        return try {
            val block = web3j.ethGetBlockByNumber(DefaultBlockParameterName.LATEST, false).send().block
            block.baseFeePerGas ?: BigInteger.ZERO
        } catch (e: Exception) {
            BigInteger.ZERO
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