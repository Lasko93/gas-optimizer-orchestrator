package de.orchestrator.gas_optimizer_orchestrator.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.utils.abi.AbiDecoder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigInteger

/**
 * Service for creating executable interactions from ABI and transaction data.
 *
 * Parses historical transactions and matches them against the contract ABI
 * to create replay-able interaction objects.
 */
@Service
class InteractionCreationService(
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(InteractionCreationService::class.java)

    companion object {
        private const val SELECTOR_LENGTH = 10 // "0x" + 8 hex chars
        private const val MIN_INPUT_LENGTH = 10 // At least selector
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Builds executable interactions from transactions and ABI.
     *
     * @param abiJson The contract ABI as JSON string
     * @param contractAddress The target contract address for interactions
     * @param transactions List of historical transactions to process
     * @return List of executable interactions that can be replayed
     */
    fun buildInteractions(
        abiJson: String,
        contractAddress: String,
        transactions: List<EtherscanTransaction>
    ): List<ExecutableInteraction> {
        val abi = parseAbi(abiJson)
        val functionsBySelector = indexFunctionsBySelector(abi)

        logger.info(
            "Building interactions for {} transactions against {} functions",
            transactions.size,
            functionsBySelector.size
        )

        val interactions = transactions
            .filter { isValidTransaction(it) }
            .mapNotNull { tx -> createInteraction(tx, contractAddress, functionsBySelector) }

        logger.info("Created {} executable interactions", interactions.size)

        return interactions
    }

    // ============================================================
    // ABI Parsing
    // ============================================================

    private fun parseAbi(abiJson: String): List<JsonNode> {
        return objectMapper.readTree(abiJson).toList()
    }

    /**
     * Creates a lookup map of function selectors to their ABI definitions.
     */
    private fun indexFunctionsBySelector(abi: List<JsonNode>): Map<String, JsonNode> {
        return abi
            .filter { it["type"]?.asText() == "function" }
            .associateBy { fn ->
                AbiDecoder.computeSelector(fn)
            }
    }

    // ============================================================
    // Transaction Filtering
    // ============================================================

    /**
     * Checks if a transaction is valid for interaction creation.
     */
    private fun isValidTransaction(tx: EtherscanTransaction): Boolean {
        val isValid = tx.input.startsWith("0x") &&
                tx.input.length > MIN_INPUT_LENGTH &&
                tx.to != null &&
                tx.isError == "0" &&
                tx.txreceipt_status == "1"

        if (!isValid) {
            logger.trace("Skipping invalid transaction: {}", tx.hash)
        }

        return isValid
    }

    // ============================================================
    // Interaction Creation
    // ============================================================

    private fun createInteraction(
        tx: EtherscanTransaction,
        contractAddress: String,
        functionsBySelector: Map<String, JsonNode>
    ): ExecutableInteraction? {
        val selector = extractSelector(tx.input)
        val functionNode = functionsBySelector[selector]

        if (functionNode == null) {
            logger.trace("No matching function for selector: {}", selector)
            return null
        }

        return try {
            buildExecutableInteraction(tx, contractAddress, selector, functionNode)
        } catch (e: Exception) {
            logger.warn("Failed to create interaction for tx {}: {}", tx.hash, e.message)
            null
        }
    }

    private fun buildExecutableInteraction(
        tx: EtherscanTransaction,
        contractAddress: String,
        selector: String,
        functionNode: JsonNode
    ): ExecutableInteraction {
        val functionName = functionNode["name"].asText()
        val inputNodes = functionNode["inputs"]
        val types = inputNodes.map { it["type"].asText() }

        val calldata = extractCalldata(tx.input)
        val decodedInputs = AbiDecoder.decodeInputs(types, calldata, inputNodes)

        logger.trace(
            "Created interaction: {}({}) from tx {}",
            functionName,
            types.joinToString(", "),
            tx.hash
        )

        return ExecutableInteraction(
            blockNumber = tx.blockNumber,
            fromAddress = tx.from,
            selector = selector,
            functionName = functionName,
            contractAddress = contractAddress,
            value = BigInteger(tx.value),
            decodedInputs = decodedInputs,
            abiTypes = types,
            tx = tx
        )
    }

    // ============================================================
    // Input Parsing Helpers
    // ============================================================

    /**
     * Extracts the 4-byte function selector from transaction input.
     */
    private fun extractSelector(input: String): String {
        return input.take(SELECTOR_LENGTH)
    }

    /**
     * Extracts the calldata (without selector) from transaction input.
     */
    private fun extractCalldata(input: String): String {
        // Remove "0x" prefix and 8 hex chars (4 bytes) of selector
        return input.removePrefix("0x").substring(8)
    }
}