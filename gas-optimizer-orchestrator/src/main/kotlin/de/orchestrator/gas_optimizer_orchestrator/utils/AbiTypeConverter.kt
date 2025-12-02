package de.orchestrator.gas_optimizer_orchestrator.utils

import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Bool
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.Type
import org.web3j.abi.datatypes.Utf8String
import org.web3j.abi.datatypes.generated.Int256
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigInteger

object AbiTypeConverter {

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
                DynamicBytes(byteArr)
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