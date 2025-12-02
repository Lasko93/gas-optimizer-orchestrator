package de.orchestrator.gas_optimizer_orchestrator.utils

import com.fasterxml.jackson.databind.JsonNode
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Dedicated helper for decoding ABI-based function inputs.
 *
 * Single responsibility:
 *  - Build selectors from ABI nodes
 *  - Decode static + dynamic Solidity input types
 *  - Decode arrays, tuples, bytes, strings
 */
object AbiInputDecoder {

    // ------------------------------------------------------------
    // SELECTOR BUILDER
    // ------------------------------------------------------------
    fun selectorFromAbi(fn: JsonNode): String {
        val name = fn["name"].asText()
        val types = fn["inputs"].joinToString(",") { it["type"].asText() }
        val signature = "$name($types)"
        val hash = Hash.sha3String(signature)
        return "0x" + hash.substring(2, 10)
    }

    // ------------------------------------------------------------
    // HIGH-LEVEL DECODE ENTRYPOINT
    // ------------------------------------------------------------
    fun decodeInputs(
        types: List<String>,
        fullData: String,
        inputsAbi: JsonNode
    ): List<Any?> {

        var offset = 0
        val out = mutableListOf<Any?>()

        types.forEachIndexed { idx, type ->

            val slot = fullData.substring(offset, offset + 64)
            val abiNode = inputsAbi[idx]

            out.add(
                decodeSingle(type, slot, fullData, abiNode)
            )

            offset += 64
        }

        return out
    }

    // ------------------------------------------------------------
    // STATIC + DYNAMIC TYPE DECODING
    // ------------------------------------------------------------
    private fun decodeSingle(
        type: String,
        slot: String,
        fullData: String,
        abiNode: JsonNode?
    ): Any? {

        return when {

            type == "address" ->
                "0x" + slot.substring(24, 64)

            type == "bool" ->
                BigInteger(slot, 16) == BigInteger.ONE

            type.startsWith("uint") || type.startsWith("int") ->
                BigInteger(slot, 16)

            type.startsWith("bytes") && !type.endsWith("[]") -> {
                Numeric.hexStringToByteArray("0x$slot")
            }

            // dynamic: string, bytes, arrays, tuple
            type == "string" || type == "bytes" ||
                    type.endsWith("[]") || type.startsWith("tuple") ->
                decodeDynamic(type, slot, fullData, abiNode)

            else ->
                error("Unsupported type: $type")
        }
    }

    private fun decodeDynamic(
        type: String,
        slot: String,
        fullData: String,
        abiNode: JsonNode?
    ): Any? {

        val offset = BigInteger(slot, 16).toInt()
        val start = offset * 2
        val lenSlot = fullData.substring(start, start + 64)
        val length = BigInteger(lenSlot, 16).toInt()

        val dataStart = start + 64
        val dataHex = fullData.substring(dataStart, dataStart + length * 2)

        return when {

            type == "string" ->
                String(Numeric.hexStringToByteArray("0x$dataHex"))

            type == "bytes" ->
                Numeric.hexStringToByteArray("0x$dataHex")

            type.endsWith("[]") ->
                decodeArray(type.removeSuffix("[]"), fullData, offset)

            type.startsWith("tuple") ->
                decodeTuple(abiNode?.get("components"), fullData, offset)

            else ->
                error("Unknown dynamic type: $type")
        }
    }

    private fun decodeArray(
        elementType: String,
        fullData: String,
        offset: Int
    ): List<Any?> {

        val start = offset * 2
        val lengthSlot = fullData.substring(start, start + 64)
        val length = BigInteger(lengthSlot, 16).toInt()

        val out = mutableListOf<Any?>()
        var pointer = start + 64

        repeat(length) {
            val slot = fullData.substring(pointer, pointer + 64)
            out.add(decodeSingle(elementType, slot, fullData, null))
            pointer += 64
        }

        return out
    }

    private fun decodeTuple(
        components: JsonNode?,
        fullData: String,
        offset: Int
    ): List<Any?>? {

        if (components == null) return null

        val start = offset * 2
        val lenSlot = fullData.substring(start, start + 64)
        val _len = BigInteger(lenSlot, 16).toInt() // not always needed

        var pointer = start + 64

        return components.map {
            val type = it["type"].asText()
            val slot = fullData.substring(pointer, pointer + 64)
            pointer += 64
            decodeSingle(type, slot, fullData, it)
        }
    }
}