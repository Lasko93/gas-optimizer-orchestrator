package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.io.File
import java.math.BigInteger

@Service
class DeployService(
    private val objectMapper: ObjectMapper,
    private val ganacheService: GanacheService
) {

    /**
     * Nimmt ein JSON-Artefakt (z. B. von Hardhat/Truffle/Foundry) entgegen
     * und deployt den enthaltenen Bytecode auf das Ganache-Netzwerk.
     *
     * @param artifactFile JSON-File mit mindestens einem `bytecode`-Feld
     * @return TransactionReceipt des neu deployten Contracts
     */

    fun deployContractFromJson(artifactFile: File): TransactionReceipt  {
        val root = objectMapper.readTree(artifactFile)

        val bytecode = extractBytecode(root)

        require(bytecode.isNotBlank() && bytecode != "0x") {
            "Kein brauchbarer Bytecode im Artefakt gefunden (leer oder nur '0x')."
        }

        val receipt = ganacheService.deployContract(
            bytecode = bytecode,
            value = BigInteger.ZERO
        )

        val contractAddress = receipt.contractAddress
            ?: throw IllegalStateException("Keine Contract-Adresse in der TransactionReceipt gefunden.")

        println("Contract deployed. TxHash=${receipt.transactionHash}, Address=$contractAddress")
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
}
