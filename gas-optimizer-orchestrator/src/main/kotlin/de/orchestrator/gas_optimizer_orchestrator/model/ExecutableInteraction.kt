package de.orchestrator.gas_optimizer_orchestrator.model

import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.utils.AbiTypeConverter
import org.web3j.abi.datatypes.Function
import org.web3j.abi.FunctionEncoder
import java.math.BigInteger

data class ExecutableInteraction(
    val blockNumber: String,
    val fromAddress: String,
    val selector: String,
    val functionName: String,
    val contractAddress: String,
    val value: BigInteger,
    val decodedInputs: List<Any?>,
    val abiTypes: List<String>,
    val tx: EtherscanTransaction
) {

    /**
     * Build a Web3j Function object from the decoded inputs.
     */
    fun toWeb3jFunction(): Function {
        val abiArgs = decodedInputs.zip(abiTypes).map { (value, type) ->
            AbiTypeConverter.toWeb3jType(value, type)
        }

        return Function(
            functionName,
            abiArgs,
            emptyList()
        )
    }

    /**
     * Encoded calldata = selector + ABI-encoded inputs
     */
    fun encoded(): String =
        FunctionEncoder.encode(toWeb3jFunction())

}