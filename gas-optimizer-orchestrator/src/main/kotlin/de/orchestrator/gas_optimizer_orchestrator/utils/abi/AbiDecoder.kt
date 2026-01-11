package de.orchestrator.gas_optimizer_orchestrator.utils.abi

import com.fasterxml.jackson.databind.JsonNode
import org.web3j.crypto.Hash
import org.web3j.utils.Numeric
import java.math.BigInteger

/**
 * Decodes ABI-encoded function inputs from transaction calldata.
 *
 * Supports:
 * - Static types: address, bool, uint/int variants, bytes1-32
 * - Dynamic types: string, bytes, arrays, tuples
 * - Function selector generation
 * - Function signature building
 */
object AbiDecoder {

    private val STATIC_BYTES_REGEX = Regex("^bytes([1-9]|[12][0-9]|3[0-2])$")

    // ============================================================
    // Function Signatures & Selectors
    // ============================================================

    /**
     * Builds a function signature string.
     * Example: "transfer(address,uint256)"
     */
    fun buildSignature(functionName: String, paramTypes: List<String>): String {
        return "$functionName(${paramTypes.joinToString(",")})"
    }

    /**
     * Computes the 4-byte function selector from an ABI function node.
     * Returns "0x" + 8 hex chars.
     */
    fun computeSelector(functionNode: JsonNode): String {
        val name = functionNode["name"].asText()
        val types = functionNode["inputs"].joinToString(",") { it["type"].asText() }
        val signature = "$name($types)"
        val hash = Hash.sha3String(signature)
        return "0x${hash.substring(2, 10)}"
    }


    // ============================================================
    // Input Decoding
    // ============================================================

    /**
     * Decodes function inputs from calldata (without selector).
     *
     * @param types List of Solidity type strings
     * @param calldata Hex string of calldata (without 0x prefix and selector)
     * @param inputsAbi ABI node containing input definitions
     * @return List of decoded values
     */
    fun decodeInputs(
        types: List<String>,
        calldata: String,
        inputsAbi: JsonNode
    ): List<Any?> {
        var offset = 0
        val results = mutableListOf<Any?>()

        types.forEachIndexed { idx, type ->
            val slot = calldata.substring(offset, offset + 64)
            val abiNode = inputsAbi[idx]
            results.add(decodeSingleValue(type, slot, calldata, abiNode))
            offset += 64
        }

        return results
    }

    // ============================================================
    // Type Detection
    // ============================================================

    private fun isStaticBytesType(type: String): Boolean {
        return STATIC_BYTES_REGEX.matches(type)
    }

    private fun getStaticBytesSize(type: String): Int? {
        return STATIC_BYTES_REGEX.find(type)?.groupValues?.get(1)?.toInt()
    }

    private fun isDynamicType(type: String): Boolean {
        return type == "string" ||
                type == "bytes" ||
                type.endsWith("[]") ||
                type.startsWith("tuple")
    }

    // ============================================================
    // Value Decoding
    // ============================================================

    private fun decodeSingleValue(
        type: String,
        slot: String,
        fullData: String,
        abiNode: JsonNode?
    ): Any? {
        return when {
            type == "address" -> decodeAddress(slot)
            type == "bool" -> decodeBool(slot)
            type.startsWith("uint") || type.startsWith("int") -> decodeInteger(slot)
            isStaticBytesType(type) -> decodeStaticBytes(slot, type)
            isDynamicType(type) -> decodeDynamicValue(type, slot, fullData, abiNode)
            else -> error("Unsupported type: $type")
        }
    }

    private fun decodeAddress(slot: String): String {
        return "0x${slot.substring(24, 64)}"
    }

    private fun decodeBool(slot: String): Boolean {
        return BigInteger(slot, 16) == BigInteger.ONE
    }

    private fun decodeInteger(slot: String): BigInteger {
        return BigInteger(slot, 16)
    }

    private fun decodeStaticBytes(slot: String, type: String): ByteArray {
        val fullSlot = Numeric.hexStringToByteArray("0x$slot")
        val size = getStaticBytesSize(type)!!
        return fullSlot.copyOfRange(0, size)
    }

    // ============================================================
    // Dynamic Type Decoding
    // ============================================================

    private fun decodeDynamicValue(
        type: String,
        slot: String,
        fullData: String,
        abiNode: JsonNode?
    ): Any? {
        val offset = BigInteger(slot, 16).toInt()
        val start = offset * 2
        val lengthSlot = fullData.substring(start, start + 64)
        val length = BigInteger(lengthSlot, 16).toInt()

        val dataStart = start + 64
        val dataHex = fullData.substring(dataStart, dataStart + length * 2)

        return when {
            type == "string" -> decodeString(dataHex)
            type == "bytes" -> decodeDynamicBytes(dataHex)
            type.endsWith("[]") -> decodeArray(type.removeSuffix("[]"), fullData, offset)
            type.startsWith("tuple") -> decodeTuple(abiNode?.get("components"), fullData, offset)
            else -> error("Unknown dynamic type: $type")
        }
    }

    private fun decodeString(dataHex: String): String {
        return String(Numeric.hexStringToByteArray("0x$dataHex"))
    }

    private fun decodeDynamicBytes(dataHex: String): ByteArray {
        return Numeric.hexStringToByteArray("0x$dataHex")
    }

    private fun decodeArray(
        elementType: String,
        fullData: String,
        offset: Int
    ): List<Any?> {
        val start = offset * 2
        val lengthSlot = fullData.substring(start, start + 64)
        val length = BigInteger(lengthSlot, 16).toInt()

        val results = mutableListOf<Any?>()
        var pointer = start + 64

        repeat(length) {
            val slot = fullData.substring(pointer, pointer + 64)
            results.add(decodeSingleValue(elementType, slot, fullData, null))
            pointer += 64
        }

        return results
    }

    private fun decodeTuple(
        components: JsonNode?,
        fullData: String,
        offset: Int
    ): List<Any?>? {
        if (components == null) return null

        val start = offset * 2
        var pointer = start + 64

        return components.map { component ->
            val type = component["type"].asText()
            val slot = fullData.substring(pointer, pointer + 64)
            pointer += 64
            decodeSingleValue(type, slot, fullData, component)
        }
    }
}