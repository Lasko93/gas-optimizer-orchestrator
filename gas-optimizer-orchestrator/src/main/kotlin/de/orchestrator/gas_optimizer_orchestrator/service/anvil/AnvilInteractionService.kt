package de.orchestrator.gas_optimizer_orchestrator.service.anvil

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.FullTransaction
import de.orchestrator.gas_optimizer_orchestrator.utils.bytecode.BytecodeUtil.validateBytecode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger

/**
 * Service for interacting with Anvil blockchain forks.
 *
 * Provides methods for:
 * - Deploying contracts on forks
 * - Sending raw transactions
 * - Executing interactions
 */
@Service
class AnvilInteractionService(
    private val web3j: Web3j,
    private val anvilManager: DockerComposeAnvilManager,
    private val gasProvider: DefaultGasProvider
) {
    private val logger = LoggerFactory.getLogger(AnvilInteractionService::class.java)

    companion object {
        private val DEFAULT_GAS_LIMIT = BigInteger("15000000")
        private val DEFAULT_FUNDING_AMOUNT = BigInteger.TEN.pow(20) // 100 ETH
        private const val RECEIPT_POLL_INTERVAL_MS = 500L
        private const val SUCCESS_STATUS = "0x1"
    }

    // ============================================================
    // Public API
    // ============================================================

    fun gasPrice(): BigInteger = gasProvider.gasPrice

    fun gasLimit(): BigInteger = DEFAULT_GAS_LIMIT

    /**
     * Deploys bytecode on an Anvil fork, replaying the original deployment context.
     *
     * @param bytecode The contract creation bytecode (with constructor args appended)
     * @param creationTx The original creation transaction for context
     * @return The transaction receipt with contract address
     * @throws IllegalStateException if deployment fails
     */
    fun deployOnFork(bytecode: String, creationTx: FullTransaction): TransactionReceipt {
        validateBytecode(bytecode)

        val forkBlock = creationTx.blockNumber - 1
        logger.info("Deploying contract on fork at block {}", forkBlock)

        setupForkEnvironment(forkBlock, creationTx.from)

        val receipt = sendContractCreation(
            from = creationTx.from,
            value = creationTx.value,
            gasPrice = creationTx.gasPrice,
            data = bytecode
        )

        validateDeploymentSuccess(receipt)

        logger.info("Deployed at {} (gas used: {})", receipt.contractAddress, receipt.gasUsed)

        return receipt
    }

    /**
     * Sends a raw transaction on the current fork.
     *
     * @param from Sender address (must be impersonated)
     * @param to Recipient address (null for contract creation)
     * @param value ETH value to send
     * @param gasLimit Gas limit for the transaction
     * @param gasPrice Gas price in wei
     * @param data Transaction data (bytecode or calldata)
     * @return The transaction receipt
     */
    fun sendRawTransaction(
        from: String,
        to: String?,
        value: BigInteger?,
        gasLimit: BigInteger?,
        gasPrice: BigInteger?,
        data: String
    ): TransactionReceipt {
        val tx = buildTransaction(from, to, value, gasLimit, gasPrice, data)

        logger.debug("Sending transaction from={}, to={}, value={}", from, to ?: "CREATE", value)

        val response = web3j.ethSendTransaction(tx).send()

        if (response.error != null) {
            logger.error("Transaction failed: {}", response.error.message)
            throw RuntimeException(response.error.message)
        }

        return waitForReceipt(response.transactionHash)
    }

    /**
     * Executes an interaction on the current fork.
     *
     * @param interaction The interaction to execute
     * @return The transaction receipt
     */
    fun sendInteraction(interaction: ExecutableInteraction): TransactionReceipt {
        val gasPrice = interaction.tx.gasPrice.toBigIntegerOrNull() ?: gasPrice()

        logger.debug(
            "Executing interaction: {} on {}",
            interaction.functionName,
            interaction.contractAddress
        )

        return sendRawTransaction(
            from = interaction.fromAddress,
            to = interaction.contractAddress,
            value = interaction.value,
            gasLimit = gasLimit(),
            gasPrice = gasPrice,
            data = interaction.encoded()
        )
    }

    // ============================================================
    // Private Helpers
    // ============================================================

    private fun setupForkEnvironment(forkBlock: Long, deployerAddress: String) {
        anvilManager.startAnvilFork(forkBlock)
        anvilManager.impersonateAccount(deployerAddress)
        anvilManager.setBalance(deployerAddress, DEFAULT_FUNDING_AMOUNT)
    }

    private fun sendContractCreation(
        from: String,
        value: BigInteger?,
        gasPrice: BigInteger,
        data: String
    ): TransactionReceipt {
        return sendRawTransaction(
            from = from,
            to = null,
            value = value,
            gasLimit = gasLimit(),
            gasPrice = gasPrice,
            data = data
        )
    }

    private fun buildTransaction(
        from: String,
        to: String?,
        value: BigInteger?,
        gasLimit: BigInteger?,
        gasPrice: BigInteger?,
        data: String
    ): Transaction {
        return if (to.isNullOrEmpty()) {
            Transaction.createContractTransaction(from, null, gasPrice, gasLimit, value, data)
        } else {
            Transaction.createFunctionCallTransaction(from, null, gasPrice, gasLimit, to, value, data)
        }
    }

    private fun validateDeploymentSuccess(receipt: TransactionReceipt) {
        if (receipt.status != SUCCESS_STATUS) {
            val message = "Deployment failed: status=${receipt.status}, gasUsed=${receipt.gasUsed}"
            logger.error(message)
            throw IllegalStateException(message)
        }
    }

    private fun waitForReceipt(txHash: String): TransactionReceipt {
        logger.trace("Waiting for receipt: {}", txHash)

        while (true) {
            val response = web3j.ethGetTransactionReceipt(txHash).send()
            val receipt = response.transactionReceipt

            if (receipt.isPresent) {
                return receipt.get()
            }

            Thread.sleep(RECEIPT_POLL_INTERVAL_MS)
        }
    }
}