package de.orchestrator.gas_optimizer_orchestrator.externalApi

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.exceptions.EtherScanException
import de.orchestrator.gas_optimizer_orchestrator.model.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.ensureOk
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.extractMethodSelector
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.looksLikeStandardJson
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.normalizeAddress
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.normalizeEtherscanSourceField
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.optionalText
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.requireNonEmptyResultArray
import de.orchestrator.gas_optimizer_orchestrator.utils.JsonHelper.extractRemappingsFromStandardJson
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class EtherScanService(
    @Value("\${etherscan.api-key}") private val apiKey: String,
    @Value("\${etherscan.base-url:https://api.etherscan.io/v2/api}") baseUrl: String,
    private val alchemyService: AlchemyService,
    webClientBuilder: RestClient.Builder
) {

    private val client: RestClient = webClientBuilder.baseUrl(baseUrl).build()
    private val mapper = jacksonObjectMapper()


    /* ============================================================
    * 0) GET SOURCE CODE FOR ADRESS
    * ============================================================ */
    fun getContractSourceCode(address: String, chainId: String = "1"): ContractSourceCodeResult {
        val trimmedAddress = address.trim()

        val rawJson = client.get()
            .uri { b ->
                b.queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", "contract")
                    .queryParam("action", "getsourcecode")
                    .queryParam("address", trimmedAddress)
                    .build()
            }
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null response from Etherscan")

        val root = mapper.readTree(rawJson)

        ensureOk(root, "getsourcecode")
        val entry = requireNonEmptyResultArray(root, "getsourcecode")[0]

        val isProxy = entry["Proxy"]?.asText() == "1"
        val impl = entry["Implementation"]?.asText()

        if (isProxy && !impl.isNullOrBlank()) {
            println("Proxy detected → following implementation $impl")
            return getContractSourceCode(impl, chainId)
        }

        val rawSourceField = optionalText(entry, "SourceCode")
        if (rawSourceField.isBlank()) {
            throw EtherScanException("0", "Contract has no verified source code on Etherscan")
        }

        val normalizedSource = normalizeEtherscanSourceField(rawSourceField)
        val isStandardJson = looksLikeStandardJson(normalizedSource)

        // Extract remappings from standard JSON input
        val remappings = if (isStandardJson) {
            extractRemappingsFromStandardJson(normalizedSource)
        } else {
            emptyList()
        }

        return ContractSourceCodeResult(
            address = trimmedAddress,
            contractName = optionalText(entry, "ContractName"),
            compilerVersion = optionalText(entry, "CompilerVersion"),
            optimizationUsed = entry["OptimizationUsed"]?.asText() == "1",
            runs = entry["Runs"]?.asInt() ?: 0,
            evmVersion = entry["EVMVersion"]?.asText(),
            sourceCode = normalizedSource,
            isStandardJsonInput = isStandardJson,
            constructorArgumentsHex = optionalText(entry, "ConstructorArguments"),
            remappings = remappings
        )
    }

    /* ============================================================
     * 1) GET TRANSACTIONS FOR ADDRESS
     * ============================================================ */
    fun getTransactionsForAddress(
        address: String,
        chainId: String = "1",
        startBlock: Long = 0,
        endBlock: Long = 9_999_999_999,
        page: Int = 1,
        offset: Int = 500,
        sort: String = "asc"
    ): List<EtherscanTransaction> {

        val trimmedAddress = normalizeAddress(address)

        val rawJson = client.get()
            .uri { b ->
                b.queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", "account")
                    .queryParam("action", "txlist")
                    .queryParam("address", trimmedAddress)
                    .queryParam("startblock", startBlock)
                    .queryParam("endblock", endBlock)
                    .queryParam("page", page)
                    .queryParam("offset", offset)
                    .queryParam("sort", sort)
                    .build()
            }
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null response from Etherscan")

        val root = mapper.readTree(rawJson)
        ensureOk(root, "txlist")
        val arr = EtherScanHelper.requireResultArray(root, "txlist")

        // Parse JSON → Kotlin objects
        val allTx = mapper
            .readerForListOf(EtherscanTransaction::class.java)
            .readValue<List<EtherscanTransaction>>(arr)

        // -------------------------------------------------------
        // FILTER: Only one transaction per unique method selector
        // -------------------------------------------------------
        val seen = mutableSetOf<String>()

        return allTx.filter { tx ->
            val selector = extractMethodSelector(tx.input) ?: return@filter false
            // only true for first occurrence of selector
            seen.add(selector)
        }
    }

    /* ============================================================
     * 2) GET ABI OF CONTRACT (V2)
     * ============================================================ */
    fun getContractAbi(address: String, chainId: String = "1"): String {
        val trimmedAddress = normalizeAddress(address)

        val rawJson = client.get()
            .uri { b ->
                b.queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", "contract")
                    .queryParam("action", "getabi")
                    .queryParam("address", trimmedAddress)
                    .build()
            }
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null response from Etherscan")

        val root = mapper.readTree(rawJson)
        ensureOk(root, "getabi")

        val resultString = root["result"]?.asText().orEmpty()

        if (resultString == "Contract source code not verified") {
            throw EtherScanException("0", "Contract not verified on Etherscan")
        }

        // Return ABI text directly
        return resultString
    }
    fun getCreationTimestampSeconds(
        contractAddress: String,
        chainId: String = "1"
    ): String {
        val txs = getTransactionsForAddress(
            address = contractAddress,
            chainId = chainId,
            startBlock = 0,
            endBlock = 9_999_999_999,
            page = 1,
            offset = 10_000,   // plenty to include the creation tx
            sort = "asc"
        )

        val creationTx = txs.minByOrNull { it.blockNumber }
            ?: throw IllegalStateException("No transactions found for $contractAddress – cannot infer creation timestamp")

        // if your timeStamp is a String/BigInteger, adapt this line:
        return creationTx.timeStamp
    }
}
