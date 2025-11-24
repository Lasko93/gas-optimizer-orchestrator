package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.RawTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import java.io.File
import java.math.BigInteger
import java.util.Optional

@Service
class ContractService(
    private val web3j: Web3j,
    private val credentials: Credentials,
    private val gasProvider: DefaultGasProvider
) {

    private val objectMapper = ObjectMapper()

    /**
     * Nimmt ein JSON-Artefakt (z. B. von Hardhat/Truffle/Foundry) entgegen
     * und deployt den enthaltenen Bytecode auf das Ganache-Netzwerk.
     *
     * @param artifactFile JSON-File mit mindestens einem `bytecode`-Feld
     * @return die Adresse des neu deployten Contracts
     */

    fun deployContractFromJson(artifactFile: File): TransactionReceipt  {
        val root = objectMapper.readTree(artifactFile)


        val bytecode = extractBytecode(root)

        require(bytecode.isNotBlank() && bytecode != "0x") {
            "Kein brauchbarer Bytecode im Artefakt gefunden (leer oder nur '0x')."
        }

        val txManager = RawTransactionManager(web3j, credentials)

        val gasPrice: BigInteger = gasProvider.gasPrice
        val gasLimit: BigInteger = gasProvider.gasLimit

        val txResponse = txManager.sendTransaction(
            gasPrice,
            gasLimit,
            "",           // to = leer → Contract-Deployment
            bytecode,     // echter Contract-Bytecode
            BigInteger.ZERO
        )

        val txHash = txResponse.transactionHash
        val receipt = waitForTransactionReceipt(txHash)

        val contractAddress = receipt.contractAddress
            ?: throw IllegalStateException("Keine Contract-Adresse in der TransactionReceipt gefunden.")

        println("Contract deployed. TxHash=$txHash, Address=$contractAddress")
        return receipt
    }

    private fun extractBytecode(root: JsonNode): String {
        // 1) Falls bytecode direkt ein String ist ("0x...")
        val direct = root.path("bytecode")
        if (direct.isTextual) {
            return normalizeHex(direct.asText())
        }

        // 2) Foundry/solc-Style: { "bytecode": { "object": "0x..." } }
        val objectNode = root.path("bytecode").path("object")
        if (objectNode.isTextual) {
            return normalizeHex(objectNode.asText())
        }

        // 3) evm.bytecode.object (solc standard JSON)
        val evmObjectNode = root.path("evm").path("bytecode").path("object")
        if (evmObjectNode.isTextual) {
            return normalizeHex(evmObjectNode.asText())
        }

        // 4) data.bytecode.object (manche Toolchains)
        val dataObjectNode = root.path("data").path("bytecode").path("object")
        if (dataObjectNode.isTextual) {
            return normalizeHex(dataObjectNode.asText())
        }

        throw IllegalArgumentException(
            "Kein Bytecode-Feld gefunden. Erwartet z.B. 'bytecode.object' oder 'evm.bytecode.object'."
        )
    }

    private fun normalizeHex(raw: String): String =
        if (raw.startsWith("0x")) raw else "0x$raw"


    private fun waitForTransactionReceipt(txHash: String): TransactionReceipt {
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
