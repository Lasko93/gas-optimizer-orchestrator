package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.FullTransaction
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil.validateBytecode
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger
import java.util.Optional

@Service
class AnvilInteractionService(
    private val web3j: Web3j,
    private val anvilManager: DockerComposeAnvilManager,
    credentials: Credentials,
    private val gasProvider: DefaultGasProvider
) {

    private val txManager = RawTransactionManager(web3j, credentials)

    fun gasPrice(): BigInteger = gasProvider.gasPrice
    fun gasLimit(): BigInteger = gasProvider.gasLimit

    /**
     * Deploys a raw bytecode string (0x prefixed) on a fresh non-forked Anvil.
     *
     * Used for:
     *  - Etherscan V2 bytecode
     *  - RPC bytecode (eth_getCode)
     */
    fun deployRawBytecode(bytecode: String,constructorArgsHex:String): TransactionReceipt {
        anvilManager.startAnvilNoFork()
        validateBytecode(bytecode)

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = bytecode,
            constructorArgsHex = constructorArgsHex
        )

        val receipt = deployContract(bytecode = deployBytecode, value = BigInteger.ZERO)

        if (receipt.status != "0x1") {
            throw IllegalStateException("Deployment failed: status=${receipt.status}, gasUsed=${receipt.gasUsed}")
        }

        val contractAddress = receipt.contractAddress
            ?: throw IllegalStateException("Anvil did not return a contract address")

        println("Contract deployed at $contractAddress")
        println("Gas used: ${receipt.gasUsed}")
        return receipt
    }

    /**
     * Deploys freshly compiled bytecode on an Anvil fork,
     * replaying the original deployment context (block, deployer, value).
     */
    fun deployOnFork(
        bytecode: String,
        creationTx: FullTransaction
    ): TransactionReceipt {
        validateBytecode(bytecode)

        val forkBlock = creationTx.blockNumber - 1
        anvilManager.startAnvilFork(forkBlock)
        anvilManager.impersonateAccount(creationTx.from)
        anvilManager.setBalance(creationTx.from, BigInteger.TEN.pow(20))


        val receipt = sendRawTransaction(
            from = creationTx.from,
            to = null,
            value = creationTx.value,
            gasLimit = gasLimit(),
            gasPrice = creationTx.gasPrice,
            data = bytecode
        )

        if (receipt.status != "0x1") {
            throw IllegalStateException("Deployment failed: status=${receipt.status}, gasUsed=${receipt.gasUsed}")
        }

        println("✔ Deployed at ${receipt.contractAddress}")
        println("  Gas used: ${receipt.gasUsed}")

        return receipt
    }

    /**
     * RAW CONTRACT DEPLOYMENT
     * to = "" and data = bytecode
     *
     * Note: does NOT start/stop Anvil. Caller decides environment (fork/no-fork).
     */
    fun deployContract(bytecode: String, value: BigInteger = BigInteger.ZERO): TransactionReceipt {
        validateBytecode(bytecode)

        val tx = txManager.sendTransaction(
            gasPrice(),
            gasLimit(),
            "",     // empty → contract deployment
            bytecode,
            value
        )

        return waitForReceipt(tx.transactionHash)
    }

    /**
     * For forked mainnet (or impersonation setups): send a tx "as any address"
     */
    fun sendRawTransaction(
        from: String,
        to: String?,
        value: BigInteger,
        gasLimit: BigInteger?,
        gasPrice: BigInteger?,
        data: String
    ): TransactionReceipt {

        val tx = if (to.isNullOrEmpty()) {
            // Contract creation
            Transaction.createContractTransaction(
                from,
                null,  // nonce
                gasPrice,
                gasLimit,
                value,
                data
            )
        } else {
            // Function call
            Transaction.createFunctionCallTransaction(
                from,
                null,
                gasPrice,
                gasLimit,
                to,
                value,
                data
            )
        }

        val send = web3j.ethSendTransaction(tx).send()
        if (send.error != null) {
            throw RuntimeException(send.error.message)
        }

        return waitForReceipt(send.transactionHash)
    }

    fun sendInteraction(interaction: ExecutableInteraction): TransactionReceipt {
        val encoded = interaction.encoded()

        val from = interaction.fromAddress
        val to = interaction.contractAddress
        val value = interaction.value

        val resolvedGasLimit = interaction.tx.gas.toBigIntegerOrNull() ?: gasLimit()
        val resolvedGasPrice = interaction.tx.gasPrice.toBigIntegerOrNull() ?: gasPrice()

        return sendRawTransaction(
            from = from,
            to = to,
            value = value,
            gasLimit = resolvedGasLimit,
            gasPrice = resolvedGasPrice,
            data = encoded
        )
    }

    /**
     * Helper for waiting on receipts (infinite polling, like your previous deploy loop).
     */
    private fun waitForReceipt(hash: String, pollMs: Long = 500L): TransactionReceipt {
        while (true) {
            val resp = web3j.ethGetTransactionReceipt(hash).send()
            val receiptOpt: Optional<TransactionReceipt> = resp.transactionReceipt
            if (receiptOpt.isPresent) return receiptOpt.get()
            Thread.sleep(pollMs)
        }
    }
}