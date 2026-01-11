package de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.exceptions.EtherScanException
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractCreationInfo
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.FullTransaction
import de.orchestrator.gas_optimizer_orchestrator.utils.etherscan.EtherscanResponseHelper
import org.springframework.stereotype.Component
import java.math.BigInteger

/**
 * Dedicated parser for Etherscan API responses.
 *
 * Separates JSON parsing logic from HTTP concerns, making the code
 * more testable and following single responsibility principle.
 */
@Component
class EtherscanResponseParser {

    private val mapper = jacksonObjectMapper()

    /**
     * Parses raw JSON into a JsonNode tree.
     */
    fun parseJson(rawJson: String): JsonNode = mapper.readTree(rawJson)

    /**
     * Parses contract source code response.
     */
    fun parseContractSourceCode(root: JsonNode, address: String): ContractSourceCodeResult {
        EtherscanResponseHelper.ensureSuccess(root, "getsourcecode")
        val entry = EtherscanResponseHelper.requireNonEmptyResultArray(root, "getsourcecode")[0]

        val isProxy = entry["Proxy"]?.asText() == EtherscanConstants.PROXY_INDICATOR
        val impl = entry["Implementation"]?.asText()

        val rawSourceField = EtherscanResponseHelper.optionalText(entry, "SourceCode")
        if (rawSourceField.isBlank()) {
            throw EtherScanException("0", "Contract has no verified source code on Etherscan")
        }

        val normalizedSource = EtherscanResponseHelper.normalizeSourceField(rawSourceField)
        val isStandardJson = EtherscanResponseHelper.isStandardJsonInput(normalizedSource)
        val remappings = if (isStandardJson) {
            EtherscanResponseHelper.extractRemappings(normalizedSource)
        } else {
            emptyList()
        }

        return ContractSourceCodeResult(
            address = address,
            contractName = EtherscanResponseHelper.optionalText(entry, "ContractName"),
            compilerVersion = EtherscanResponseHelper.optionalText(entry, "CompilerVersion"),
            optimizationUsed = entry["OptimizationUsed"]?.asText() == EtherscanConstants.OPTIMIZATION_ENABLED,
            runs = entry["Runs"]?.asInt() ?: 0,
            evmVersion = entry["EVMVersion"]?.asText(),
            sourceCode = normalizedSource,
            isStandardJsonInput = isStandardJson,
            constructorArgumentsHex = EtherscanResponseHelper.optionalText(entry, "ConstructorArguments"),
            remappings = remappings,
            isProxy = isProxy,
            implementationAddress = impl
        )
    }

    /**
     * Parses transaction list response, filtering for unique method selectors.
     */
    fun parseTransactions(root: JsonNode): List<EtherscanTransaction> {
        EtherscanResponseHelper.ensureSuccess(root, "txlist")
        val arr = EtherscanResponseHelper.requireResultArray(root, "txlist")

        val allTx: List<EtherscanTransaction> = mapper
            .readerForListOf(EtherscanTransaction::class.java)
            .readValue(arr)

        val seen = mutableSetOf<String>()
        return allTx.filter { tx ->
            val selector = EtherscanResponseHelper.extractMethodSelector(tx.input) ?: return@filter false
            seen.add(selector)
        }
    }

    /**
     * Parses ABI response.
     */
    fun parseAbi(root: JsonNode): String {
        EtherscanResponseHelper.ensureSuccess(root, "getabi")
        val resultString = root["result"]?.asText().orEmpty()

        if (resultString == EtherscanConstants.CONTRACT_NOT_VERIFIED_MESSAGE) {
            throw EtherScanException("0", "Contract not verified on Etherscan")
        }

        return resultString
    }

    /**
     * Parses contract creation info response.
     */
    fun parseContractCreationInfo(root: JsonNode): ContractCreationInfo {
        EtherscanResponseHelper.ensureSuccess(root, "getcontractcreation")
        val arr = EtherscanResponseHelper.requireNonEmptyResultArray(root, "getcontractcreation")
        val entry = arr[0]

        return ContractCreationInfo(
            contractAddress = entry["contractAddress"]?.asText()
                ?: throw EtherScanException("0", "Missing contractAddress in response"),
            contractCreator = entry["contractCreator"]?.asText()
                ?: throw EtherScanException("0", "Missing contractCreator in response"),
            txHash = entry["txHash"]?.asText()
                ?: throw EtherScanException("0", "Missing txHash in response")
        )
    }

    /**
     * Parses full transaction response (from eth_getTransactionByHash).
     */
    fun parseFullTransaction(root: JsonNode, txHash: String): FullTransaction {
        val result = root["result"]
            ?: throw EtherScanException("0", "Transaction not found: $txHash")

        return FullTransaction(
            hash = result["hash"].asText(),
            from = result["from"].asText(),
            to = result["to"]?.asText(),
            value = parseHexBigInteger(result["value"]?.asText()),
            gas = parseHexBigInteger(result["gas"]?.asText()) ?: BigInteger.ZERO,
            gasPrice = parseHexBigInteger(result["gasPrice"]?.asText()) ?: BigInteger.ZERO,
            input = result["input"].asText(),
            nonce = parseHexBigInteger(result["nonce"]?.asText()) ?: BigInteger.ZERO,
            blockNumber = parseHexLong(result["blockNumber"]?.asText()) ?: 0L
        )
    }

    private fun parseHexBigInteger(hex: String?): BigInteger? {
        if (hex.isNullOrBlank()) return null
        return hex.removePrefix("0x").toBigIntegerOrNull(16)
    }

    private fun parseHexLong(hex: String?): Long? {
        if (hex.isNullOrBlank()) return null
        return hex.removePrefix("0x").toLongOrNull(16)
    }
}