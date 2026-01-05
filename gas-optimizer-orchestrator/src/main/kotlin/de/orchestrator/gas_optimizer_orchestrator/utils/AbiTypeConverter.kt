package de.orchestrator.gas_optimizer_orchestrator.utils

import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Bytes1
import org.web3j.abi.datatypes.generated.Bytes2
import org.web3j.abi.datatypes.generated.Bytes3
import org.web3j.abi.datatypes.generated.Bytes4
import org.web3j.abi.datatypes.generated.Bytes5
import org.web3j.abi.datatypes.generated.Bytes6
import org.web3j.abi.datatypes.generated.Bytes7
import org.web3j.abi.datatypes.generated.Bytes8
import org.web3j.abi.datatypes.generated.Bytes9
import org.web3j.abi.datatypes.generated.Bytes10
import org.web3j.abi.datatypes.generated.Bytes11
import org.web3j.abi.datatypes.generated.Bytes12
import org.web3j.abi.datatypes.generated.Bytes13
import org.web3j.abi.datatypes.generated.Bytes14
import org.web3j.abi.datatypes.generated.Bytes15
import org.web3j.abi.datatypes.generated.Bytes16
import org.web3j.abi.datatypes.generated.Bytes17
import org.web3j.abi.datatypes.generated.Bytes18
import org.web3j.abi.datatypes.generated.Bytes19
import org.web3j.abi.datatypes.generated.Bytes20
import org.web3j.abi.datatypes.generated.Bytes21
import org.web3j.abi.datatypes.generated.Bytes22
import org.web3j.abi.datatypes.generated.Bytes23
import org.web3j.abi.datatypes.generated.Bytes24
import org.web3j.abi.datatypes.generated.Bytes25
import org.web3j.abi.datatypes.generated.Bytes26
import org.web3j.abi.datatypes.generated.Bytes27
import org.web3j.abi.datatypes.generated.Bytes28
import org.web3j.abi.datatypes.generated.Bytes29
import org.web3j.abi.datatypes.generated.Bytes30
import org.web3j.abi.datatypes.generated.Bytes31
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Int256
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigInteger

object AbiTypeConverter {

    private val STATIC_BYTES_REGEX = Regex("^bytes([1-9]|[12][0-9]|3[0-2])$")

    private fun extractBytesSize(type: String): Int? {
        val match = STATIC_BYTES_REGEX.find(type)
        return match?.groupValues?.get(1)?.toInt()
    }

    private fun createStaticBytesType(size: Int, value: ByteArray): Type<*> {
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
            else -> throw IllegalArgumentException(
                "Invalid static bytes size: $size (must be 1-32)"
            )
        }
    }

    fun toWeb3jType(value: Any?, type: String): Type<*> {

        return when {

            type == "address" ->
                Address(value as String)

            type.startsWith("uint") ->
                Uint256(value as BigInteger)

            type.startsWith("int") ->
                Int256(value as BigInteger)

            type == "bool" ->
                Bool(value as Boolean)

            type == "string" ->
                Utf8String(value as String)

            type.startsWith("bytes") && !type.endsWith("[]") -> {
                val byteArr = value as ByteArray
                val size = extractBytesSize(type)

                if (size != null) {
                    // Static bytes (bytes1 through bytes32)
                    createStaticBytesType(size, byteArr)
                } else if (type == "bytes") {
                    // Dynamic bytes
                    DynamicBytes(byteArr)
                } else {
                    throw IllegalArgumentException("Invalid bytes type: $type")
                }
            }

            // ARRAY
            type.endsWith("[]") -> {
                val baseType = type.removeSuffix("[]")
                val list = value as List<*>

                val converted = list.map { element ->
                    toWeb3jType(element, baseType)
                }

                DynamicArray(converted)
            }

            // TUPLE
            type.startsWith("tuple") -> {
                val components = value as List<*>
                val structValues = components.map { it as Type<*> }
                DynamicStruct(*structValues.toTypedArray())
            }

            else -> throw IllegalArgumentException("Unsupported ABI type: $type")
        }
    }
}