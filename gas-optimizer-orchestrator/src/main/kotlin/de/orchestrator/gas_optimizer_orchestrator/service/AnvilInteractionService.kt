package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.request.Transaction
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.tx.response.PollingTransactionReceiptProcessor
import java.math.BigInteger
import java.util.Optional

@Service
class AnvilInteractionService(
    private val web3j: Web3j,
    credentials: Credentials,
    private val gasProvider: DefaultGasProvider
) {

    private val txManager = RawTransactionManager(web3j, credentials)
    private val accounts: List<String> = web3j.ethAccounts().send().accounts


    fun gasPrice(): BigInteger = gasProvider.gasPrice
    fun gasLimit(): BigInteger = gasProvider.gasLimit

    /**
     * RAW CONTRACT DEPLOYMENT
     * to = "" and data = bytecode
     */
    fun deployContract(bytecode: String, value: BigInteger = BigInteger.ZERO): TransactionReceipt {

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
     * For forked mainnet: send a tx "as any address"
     */
    fun sendRawTransaction(
        from: String,
        to: String,
        value: BigInteger,
        gasLimit: BigInteger?,
        gasPrice: BigInteger?,
        data: String
    ): TransactionReceipt {

        val tx = Transaction.createFunctionCallTransaction(
            from,
            null,
            gasPrice,
            gasLimit,
            to,
            value,
            data
        )

        val send = web3j.ethSendTransaction(tx).send()
        if (send.error != null) {
            throw RuntimeException(send.error.message)
        }

        val txHash = send.transactionHash
        val processor = PollingTransactionReceiptProcessor(web3j, 1000L, 40)
        return processor.waitForTransactionReceipt(txHash)
    }

    fun sendInteraction(interaction: ExecutableInteraction): TransactionReceipt {

        val encoded = interaction.encoded()

        val from = accounts[0]
        val to = interaction.contractAddress
        val value = interaction.value

        val gasLimit =
            interaction.tx.gas.toBigIntegerOrNull() ?: gasLimit()

        val gasPrice =
            interaction.tx.gasPrice.toBigIntegerOrNull() ?: gasPrice()

        return sendRawTransaction(
            from = from,
            to = to,
            value = value,
            gasLimit = gasLimit,
            gasPrice = gasPrice,
            data = encoded
        )
    }

    /**
     * Helper for waiting on receipts.
     */
    private fun waitForReceipt(hash: String): TransactionReceipt {
        var receiptOpt: Optional<TransactionReceipt>

        while (true) {
            val resp = web3j.ethGetTransactionReceipt(hash).send()
            receiptOpt = resp.transactionReceipt
            if (receiptOpt.isPresent) return receiptOpt.get()
            Thread.sleep(300)
        }
    }
}