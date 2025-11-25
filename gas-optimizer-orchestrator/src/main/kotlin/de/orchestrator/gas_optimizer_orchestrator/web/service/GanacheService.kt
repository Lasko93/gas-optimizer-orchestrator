package de.orchestrator.gas_optimizer_orchestrator.web.service

import org.springframework.stereotype.Service
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.datatypes.Function as Web3Function
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger
import java.util.Optional

@Service
class GanacheService(
    private val web3j: Web3j,
    private val credentials: Credentials,
    private val gasProvider: DefaultGasProvider
) {

    private val txManager = RawTransactionManager(web3j, credentials)
    private val accounts: List<String> = web3j.ethAccounts().send().accounts

    fun getAccounts(): List<String> = accounts

    fun gasPrice(): BigInteger = gasProvider.gasPrice
    fun gasLimit(): BigInteger = gasProvider.gasLimit

    /**
     * Sendet einen Funktions-Call an einen existierenden Contract.
     */
    fun sendFunctionTx(
        contractAddress: String,
        function: Web3Function,
        value: BigInteger = BigInteger.ZERO
    ): TransactionReceipt {
        val encoded = FunctionEncoder.encode(function)
        val txResponse = txManager.sendTransaction(
            gasPrice(),
            gasLimit(),
            contractAddress,
            encoded,
            value
        )
        return waitForReceipt(txResponse.transactionHash)
    }

    /**
     * Deployt einen Contract-Bytecode (to = "", data = bytecode).
     */
    fun deployContract(
        bytecode: String,
        value: BigInteger = BigInteger.ZERO
    ): TransactionReceipt {
        val txResponse = txManager.sendTransaction(
            gasPrice(),
            gasLimit(),
            "",          // leere Adresse = Contract-Deployment
            bytecode,
            value
        )
        return waitForReceipt(txResponse.transactionHash)
    }

    private fun waitForReceipt(txHash: String): TransactionReceipt {
        var receiptOpt: Optional<TransactionReceipt>
        while (true) {
            val response = web3j.ethGetTransactionReceipt(txHash).send()
            receiptOpt = response.transactionReceipt
            if (receiptOpt.isPresent) {
                return receiptOpt.get()
            }
            Thread.sleep(500)
        }
    }
}
