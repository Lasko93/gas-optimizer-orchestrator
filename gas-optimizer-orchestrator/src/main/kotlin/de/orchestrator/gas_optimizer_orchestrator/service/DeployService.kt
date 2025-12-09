package de.orchestrator.gas_optimizer_orchestrator.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.io.File
import java.math.BigInteger

@Service
class DeployService(
    private val anvilInteractionService: AnvilInteractionService
) {

    private val objectMapper = ObjectMapper()

    /**
     * Deploys a raw bytecode string (0x prefixed).
     *
     * This is used for:
     *  - Etherscan V2 bytecode
     *  - Alchemy RPC bytecode (eth_getCode)
     */
    fun deployRawBytecode(bytecode: String): TransactionReceipt {

        require(bytecode.startsWith("0x")) {
            "Bytecode must start with 0x"
        }
        require(bytecode.length > 4) {
            "Bytecode too short; contract does not exist."
        }

        val receipt = anvilInteractionService.deployContract(
            bytecode = bytecode,
            value = BigInteger.ZERO
        )

        val contractAddress = receipt.contractAddress
            ?: throw IllegalStateException("Anvil did not return a contract address")

        println("Contract deployed at $contractAddress")
        println("Gas used: ${receipt.gasUsed}")
        return receipt
    }
    /**
     * Nimmt ein JSON-Artefakt (z. B. von Hardhat/Truffle/Foundry) entgegen
     * und deployt den enthaltenen Bytecode auf das Ganache-Netzwerk.
     *
     * @param artifactFile JSON-File mit mindestens einem `bytecode`-Feld
     * @return TransactionReceipt des neu deployten Contracts
     */

    fun deployContractFromJson(
        artifactFile: File,
        constructorArgsHex: String? = null
    ): TransactionReceipt {

        val root = objectMapper.readTree(artifactFile)
        val baseBytecode = extractBytecode(root) // must be CREATION bytecode, e.g. evm.bytecode.object

        require(baseBytecode.isNotBlank() && baseBytecode != "0x") {
            "Kein brauchbarer Bytecode im Artefakt gefunden (leer oder nur '0x')."
        }

        val fullBytecode = if (!constructorArgsHex.isNullOrBlank()) {
            val code = baseBytecode.removePrefix("0x")
            val args = constructorArgsHex.removePrefix("0x")
            "0x$code$args"
        } else {
            baseBytecode
        }

        println("Deploying creation bytecode (${fullBytecode})")
        val receipt = anvilInteractionService.deployContract(
            bytecode = fullBytecode,
            value = BigInteger.ZERO
        )

        val contractAddress = receipt.contractAddress
            ?: error("Keine Contract-Adresse in der TransactionReceipt gefunden. Status=${receipt.status}")

        println("Contract deployed. TxHash=${receipt.transactionHash}, Address=$contractAddress, GasUsed=${receipt.gasUsed}")
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

    /**
     * Wrap a runtime bytecode blob into a generic constructor that just
     * copies it into memory and returns it as the contract code.
     *
     * Input:  runtime like "0x60806040..."
     * Output: creation code like "0x61....61....60003961....6000f3<runtime>"
     */
    private fun wrapRuntimeAsCreation(runtimeBytecode: String): String {
        val runtime = runtimeBytecode.removePrefix("0x")
        require(runtime.length % 2 == 0) { "Runtime hex length must be even" }

        val lenBytes = runtime.length / 2
        require(lenBytes <= 0xFFFF) {
            "Runtime too large ($lenBytes bytes) – exceeds PUSH2 range"
        }

        val lenHex = lenBytes.toString(16).padStart(4, '0')  // 2 bytes
        val offsetHex = "000f"                               // stub is 15 bytes

        val creationWithoutPrefix =
            "61$lenHex" +      // PUSH2 len
                    "61$offsetHex" +   // PUSH2 offset (15)
                    "6000" +           // PUSH1 0
                    "39" +             // CODECOPY
                    "61$lenHex" +      // PUSH2 len
                    "6000" +           // PUSH1 0
                    "f3" +             // RETURN
                    runtime            // runtime code

        return "0x$creationWithoutPrefix"
    }

    /**
     * Deploys a runtime-optimized bytecode (e.g. from bytepeep).
     */
    fun deployOptimizedRuntime(runtimeBytecode: String): TransactionReceipt {
        val normalizedRuntime = normalizeHex(runtimeBytecode)
        val creation = wrapRuntimeAsCreation(normalizedRuntime)

        println("Deploying optimized runtime via stub (creation size=${(creation.length - 2) / 2} bytes)")
        return deployRawBytecode(creation)
    }

}
