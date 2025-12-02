package de.orchestrator.gas_optimizer_orchestrator.service

import org.springframework.stereotype.Service
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigInteger

@Service
class DeployService(
    private val anvilInteractionService: AnvilInteractionService
) {

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
            ?: throw IllegalStateException("Ganache did not return a contract address")

        println("Contract deployed at $contractAddress")
        return receipt
    }
}
