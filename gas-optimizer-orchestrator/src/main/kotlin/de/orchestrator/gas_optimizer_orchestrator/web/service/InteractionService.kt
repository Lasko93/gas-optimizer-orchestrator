package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.model.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.utils.AbiInputDecoder
import org.springframework.stereotype.Service
import java.math.BigInteger

@Service
class InteractionService(
    private val objectMapper: ObjectMapper,
) {

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

                val fn = abi.find {
                    it["type"].asText() == "function" &&
                            selector == AbiInputDecoder.selectorFromAbi(it)
                } ?: return@mapNotNull null

                val fnName = fn["name"].asText()
                val inputNodes = fn["inputs"]
                val types = inputNodes.map { it["type"].asText() }

                // remove 4-byte selector
                val fullData = tx.input.removePrefix("0x").substring(8)

                val decoded = AbiInputDecoder.decodeInputs(types, fullData, inputNodes)

                ExecutableInteraction(
                    blockNumber = tx.blockNumber,
                    selector = selector,
                    functionName = fnName,
                    contractAddress = contractAddress,
                    value = BigInteger(tx.value),
                    decodedInputs = decoded,
                    abiTypes = types,
                    tx = tx
                )
            }
    }
}