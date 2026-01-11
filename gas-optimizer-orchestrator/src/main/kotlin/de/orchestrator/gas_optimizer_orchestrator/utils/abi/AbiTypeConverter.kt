package de.orchestrator.gas_optimizer_orchestrator.utils.abi

import org.web3j.abi.datatypes.*
import org.web3j.abi.datatypes.generated.*
import java.math.BigInteger

/**
 * Converts decoded ABI values to Web3j Type instances for encoding.
 *
 * Used when re-encoding transaction inputs for replay.
 */
object AbiTypeConverter {

    private val STATIC_BYTES_REGEX = Regex("^bytes([1-9]|[12][0-9]|3[0-2])$")

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Converts a decoded value to its Web3j Type representation.
     *
     * @param value The decoded value
     * @param type The Solidity type string
     * @return Web3j Type instance
     */
    fun toWeb3jType(value: Any?, type: String): Type<*> {
        return when {
            type == "address" -> convertAddress(value)
            type.startsWith("uint") -> convertUint(value)
            type.startsWith("int") -> convertInt(value)
            type == "bool" -> convertBool(value)
            type == "string" -> convertString(value)
            type == "bytes" -> convertDynamicBytes(value)
            isStaticBytesType(type) -> convertStaticBytes(value, type)
            type.endsWith("[]") -> convertArray(value, type)
            type.startsWith("tuple") -> convertTuple(value)
            else -> throw IllegalArgumentException("Unsupported ABI type: $type")
        }
    }

    // ============================================================
    // Type Converters
    // ============================================================

    private fun convertAddress(value: Any?): Address {
        return Address(value as String)
    }

    private fun convertUint(value: Any?): Uint256 {
        return Uint256(value as BigInteger)
    }

    private fun convertInt(value: Any?): Int256 {
        return Int256(value as BigInteger)
    }

    private fun convertBool(value: Any?): Bool {
        return Bool(value as Boolean)
    }

    private fun convertString(value: Any?): Utf8String {
        return Utf8String(value as String)
    }

    private fun convertDynamicBytes(value: Any?): DynamicBytes {
        return DynamicBytes(value as ByteArray)
    }

    private fun convertStaticBytes(value: Any?, type: String): Type<*> {
        val byteArray = value as ByteArray
        val size = getStaticBytesSize(type)
            ?: throw IllegalArgumentException("Invalid bytes type: $type")
        return createStaticBytesInstance(size, byteArray)
    }

    private fun convertArray(value: Any?, type: String): DynamicArray<Type<*>> {
        val baseType = type.removeSuffix("[]")
        val list = value as List<*>
        val converted = list.map { element -> toWeb3jType(element, baseType) }
        return DynamicArray(converted)
    }

    private fun convertTuple(value: Any?): DynamicStruct {
        val components = value as List<*>
        val structValues = components.map { it as Type<*> }
        return DynamicStruct(*structValues.toTypedArray())
    }

    // ============================================================
    // Static Bytes Factory
    // ============================================================

    private fun isStaticBytesType(type: String): Boolean {
        return STATIC_BYTES_REGEX.matches(type)
    }

    private fun getStaticBytesSize(type: String): Int? {
        return STATIC_BYTES_REGEX.find(type)?.groupValues?.get(1)?.toInt()
    }

    /**
     * Creates a Web3j static bytes instance (Bytes1 through Bytes32).
     */
    private fun createStaticBytesInstance(size: Int, value: ByteArray): Type<*> {
        return when (size) {
            1 -> Bytes1(value)
            2 -> Bytes2(value)
            3 -> Bytes3(value)
            4 -> Bytes4(value)
            5 -> Bytes5(value)
            6 -> Bytes6(value)
            7 -> Bytes7(value)
            8 -> Bytes8(value)
            9 -> Bytes9(value)
            10 -> Bytes10(value)
            11 -> Bytes11(value)
            12 -> Bytes12(value)
            13 -> Bytes13(value)
            14 -> Bytes14(value)
            15 -> Bytes15(value)
            16 -> Bytes16(value)
            17 -> Bytes17(value)
            18 -> Bytes18(value)
            19 -> Bytes19(value)
            20 -> Bytes20(value)
            21 -> Bytes21(value)
            22 -> Bytes22(value)
            23 -> Bytes23(value)
            24 -> Bytes24(value)
            25 -> Bytes25(value)
            26 -> Bytes26(value)
            27 -> Bytes27(value)
            28 -> Bytes28(value)
            29 -> Bytes29(value)
            30 -> Bytes30(value)
            31 -> Bytes31(value)
            32 -> Bytes32(value)
            else -> throw IllegalArgumentException("Invalid static bytes size: $size (must be 1-32)")
        }
    }
}