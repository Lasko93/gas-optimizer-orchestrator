package de.orchestrator.gas_optimizer_orchestrator.externalApi

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.exceptions.EtherScanException
import de.orchestrator.gas_optimizer_orchestrator.model.EtherscanTransaction
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
    fun getContractSourceCode(address: String, chainId: String = "1"): String {

        val rawJson = client.get()
            .uri { b ->
                b.queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", "contract")
                    .queryParam("action", "getsourcecode")
                    .queryParam("address", address.trim())
                    .build()
            }
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null response from Etherscan")

        val root = mapper.readTree(rawJson)
        val status = root["status"]?.asText() ?: "0"
        val result = root["result"]

        if (status != "1" || result == null || !result.isArray || result.size() == 0) {
            throw EtherScanException(status, root["message"]?.asText() ?: "Failed to fetch source code")
        }

        val entry = result[0]

        // ============================================================
        // 1. Proxy detection → follow implementation
        // ============================================================
        val isProxy = entry["Proxy"]?.asText() == "1"
        val impl = entry["Implementation"]?.asText()

        if (isProxy && !impl.isNullOrBlank()) {
            println("Proxy detected → following implementation $impl")
            return getContractSourceCode(impl, chainId)
        }

        // ============================================================
        // 2. Extract Solidity source
        // ============================================================
        val sourceCode = entry["SourceCode"]?.asText() ?: ""

        if (sourceCode.isBlank()) {
            throw EtherScanException("0", "Contract has no verified source code on Etherscan")
        }
        return sourceCode
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

        val rawJson = client.get()
            .uri { b ->
                b.queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", "account")
                    .queryParam("action", "txlist")
                    .queryParam("address", address)
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
        val status = root["status"]?.asText() ?: "0"

        if (status != "1") {
            throw EtherScanException(
                status,
                root["message"]?.asText() ?: "Etherscan error"
            )
        }

        val arr = root["result"]
        if (arr == null || !arr.isArray) {
            throw IllegalStateException("Unexpected Etherscan result: $rawJson")
        }

        // Parse JSON → Kotlin objects

        val allTx = mapper.readerForListOf(EtherscanTransaction::class.java).readValue<List<EtherscanTransaction>>(arr)

        // -------------------------------------------------------
        // FILTER: Only one transaction per unique method selector
        // -------------------------------------------------------
        val seen = mutableSetOf<String>()

        return allTx.filter { tx ->
            // ignore empty calldata
            if (tx.input.isNullOrBlank() || tx.input == "0x") return@filter false

            // extract method selector: first 10 chars (“0x + 8 hex")
            val selector = tx.input.take(10)

            // return only first occurrence per selector
            seen.add(selector)
        }
    }

    /* ============================================================
     * 2) GET ABI OF CONTRACT (V2)
     * ============================================================ */
    fun getContractAbi(address: String, chainId: String = "1"): String {
        val rawJson = client.get()
            .uri { b ->
                b.queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", "contract")
                    .queryParam("action", "getabi")
                    .queryParam("address", address.trim())
                    .build()
            }
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null response from Etherscan")

        val root = mapper.readTree(rawJson)
        val status = root["status"]?.asText() ?: "0"
        if (status != "1") {
            throw EtherScanException(status, root["message"]?.asText() ?: "Failed to fetch ABI")
        }

        val resultString = root["result"].asText()

        if (resultString == "Contract source code not verified") {
            throw EtherScanException("0", "Contract not verified on Etherscan")
        }

        // Return ABI text directly
        return resultString
    }

    /* ============================================================
     * 3) GET BYTECODE OF CONTRACT (V2)
     * ============================================================ */
    fun getContractBytecode(address: String, chainId: String = "1"): String {

        val rawJson = client.get()
            .uri { b ->
                b.queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", "contract")
                    .queryParam("action", "getsourcecode")
                    .queryParam("address", address)
                    .build()
            }
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null response from Etherscan")

        val root = mapper.readTree(rawJson)
        val status = root["status"]?.asText() ?: "0"

        val result = root["result"]
        val entry = result?.get(0)

        // ------------------------------------------------
        // 1. Proxy detection (V2 standard)
        // ------------------------------------------------
        if (entry != null) {
            val isProxy = entry["Proxy"]?.asText() == "1"
            val impl = entry["Implementation"]?.asText()
            if (isProxy && !impl.isNullOrBlank()) {
                println("Proxy detected → following implementation $impl")
                return getContractBytecode(impl, chainId)
            }
        }

        // ------------------------------------------------
        // 2. Etherscan says: NOT VERIFIED or empty bytecode
        // ------------------------------------------------
        val bytecode = entry?.get("Bytecode")?.asText() ?: ""
        if (bytecode.isBlank() || bytecode == "0x") {
            println("Etherscan has no verified bytecode → falling back to eth_getCode()...")

            val rpcBytecode = alchemyService.getBytecode(address)

            if (rpcBytecode == "0x" || rpcBytecode.isBlank()) {
                throw EtherScanException("0", "Bytecode missing and rpc fallback returned empty code")
            }

            return rpcBytecode
        }

        return if (bytecode.startsWith("0x")) bytecode else "0x$bytecode"
    }
}
