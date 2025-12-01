package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.model.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import org.springframework.stereotype.Service
import org.web3j.abi.datatypes.Type
import org.web3j.utils.Numeric
import java.math.BigInteger

@Service
class InteractionService(
    private val objectMapper: ObjectMapper,
) {

    /**
     * Hauptmethode: Etherscan-Txs + ABI => Web3j-fertige Aufrufe
     */
    fun buildInteractions(
        abiJson: String,
        contractAddress: String,
        transactions: List<EtherscanTransaction>
    ): List<ExecutableInteraction> {

        val abi: List<JsonNode> = objectMapper.readTree(abiJson).toList()

        return transactions
            .filter { it.input.startsWith("0x") && it.input.length > 10 }
            .mapNotNull { tx ->

                val selector = tx.input.substring(0, 10)

                // ABI-Funktion anhand des Selectors finden
                val fn = abi.find {
                    it["type"].asText() == "function" &&
                            selector == buildSelectorFromAbi(it)
                } ?: return@mapNotNull null

                val functionName = fn["name"].asText()
                val inputsAbi = fn["inputs"]

                // TYPES extrahieren
                val types = inputsAbi.map { it["type"].asText() }

                // HEX ohne 4-byte selector
                val data = tx.input.removePrefix("0x").substring(8)

                // DECODE
                val decoded = decodeInputs(types, data, inputsAbi)

                ExecutableInteraction(
                    selector = selector,
                    functionName = functionName,
                    contractAddress = contractAddress,
                    value = BigInteger(tx.value),
                    decodedInputs = decoded,
                    abiTypes = types,
                    tx = tx
                )
            }
    }

    // ------------------------------------------------------------
    //                   METHOD SELECTOR BUILDER
    // ------------------------------------------------------------
    private fun buildSelectorFromAbi(node: JsonNode): String {
        val name = node["name"].asText()
        val types = node["inputs"].joinToString(",") { it["type"].asText() }
        val signature = "$name($types)"
        val hash = org.web3j.crypto.Hash.sha3String(signature)
        return "0x" + hash.substring(2, 10)
    }

    // ------------------------------------------------------------
    //                    INPUT DECODING LOGIC
    // ------------------------------------------------------------

    private fun decodeInputs(
        types: List<String>,
        data: String,
        inputsAbi: JsonNode
    ): List<Any?> {

        var offset = 0
        val results = mutableListOf<Any?>()

        types.forEachIndexed { index, type ->
            val slot = data.substring(offset, offset + 64)
            val value = decodeSingle(type, slot, data, inputsAbi[index])
            results.add(value)
            offset += 64
        }

        return results
    }

    private fun decodeSingle(
        type: String,
        slot: String,
        fullData: String,
        abiNode: JsonNode?
    ): Any? {

        return when {

            type == "address" ->
                "0x" + slot.substring(24, 64)

            type.startsWith("uint") ->
                BigInteger(slot, 16)

            type.startsWith("int") ->
                BigInteger(slot, 16)

            type == "bool" ->
                (BigInteger(slot, 16) == BigInteger.ONE)

            // fixed 32 bytes
            type.startsWith("bytes") && !type.endsWith("[]") -> {
                val bytesHex = "0x" + slot
                Numeric.hexStringToByteArray(bytesHex)
            }

            // dynamic types (string, bytes, arrays, tuple)
            type == "string" || type == "bytes" || type.endsWith("[]") || type.startsWith("tuple") -> {
                decodeDynamic(type, slot, fullData, abiNode)
            }

            else -> error("Unsupported type: $type")
        }
    }

    // ------------------------------------------------------------
    //                DYNAMIC TYPE DECODING
    // ------------------------------------------------------------
    private fun decodeDynamic(
        type: String,
        slot: String,
        fullData: String,
        abiNode: JsonNode?
    ): Any? {

        val offset = BigInteger(slot, 16).toInt()
        val start = offset * 2             // hex chars
        val lenSlot = fullData.substring(start, start + 64)
        val length = BigInteger(lenSlot, 16).toInt()

        val contentStart = start + 64
        val contentHex = fullData.substring(contentStart, contentStart + length * 2)

        return when {
            type == "string" ->
                String(Numeric.hexStringToByteArray("0x$contentHex"))

            type == "bytes" ->
                Numeric.hexStringToByteArray("0x$contentHex")

            type.endsWith("[]") -> {
                decodeArray(type, fullData, offset)
            }

            type.startsWith("tuple") -> {
                decodeTuple(abiNode?.get("components") ?: null , fullData, offset)
            }

            else -> error("Unsupported dynamic type: $type")
        }
    }

    private fun decodeArray(type: String, data: String, offset: Int): List<Any?> {
        val arrayStart = offset * 2
        val lengthSlot = data.substring(arrayStart, arrayStart + 64)
        val length = BigInteger(lengthSlot, 16).toInt()

        val values = mutableListOf<Any?>()

        val elementType = type.removeSuffix("[]")

        var pointer = arrayStart + 64

        repeat(length) {
            val slot = data.substring(pointer, pointer + 64)
            values.add(decodeSingle(elementType, slot, data, abiNode = null))
            pointer += 64
        }

        return values
    }

    private fun decodeTuple(components: JsonNode?, fullData: String, offset: Int): List<Any?>? {
        val tupleStart = offset * 2
        val lengthSlot = fullData.substring(tupleStart, tupleStart + 64)
        val length = BigInteger(lengthSlot, 16).toInt()

        var pointer = tupleStart + 64

        val values = components?.map {
            val slot = fullData.substring(pointer, pointer + 64)
            val t = it["type"].asText()
            val decoded = decodeSingle(t, slot, fullData, it)
            pointer += 64
            decoded
        }

        return values
    }
}
