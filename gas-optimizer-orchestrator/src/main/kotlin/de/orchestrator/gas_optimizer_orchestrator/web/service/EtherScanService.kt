package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.domain.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.exceptions.EtherScanException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class EtherScanService(
    @Value("\${etherscan.api-key}") private val apiKey: String,
    // V2 base URL
    @Value("\${etherscan.base-url:https://api.etherscan.io/v2/api}") baseUrl: String,
    webClientBuilder: RestClient.Builder
) {

    private val client: RestClient = webClientBuilder
        .baseUrl(baseUrl)
        .build()

    private val mapper = jacksonObjectMapper()

    /**
     * V2:
     * GET https://api.etherscan.io/v2/api
     *   ?chainid=1
     *   &module=account
     *   &action=txlist
     *   &address=<contractAddress>
     *   &startblock=0
     *   &endblock=9999999999
     *   &page=1
     *   &offset=100
     *   &sort=asc
     *   &apikey=<YourApiKeyToken>
     */
    fun getTransactionsForAddress(
        address: String,
        chainId: String = "1",             // 1 = Ethereum mainnet
        startBlock: Long = 0,
        endBlock: Long = 9_999_999_999,
        page: Int = 1,
        offset: Int = 10000,
        sort: String = "asc"
    ): List<EtherscanTransaction> {

        val rawJson = client.get()
            .uri { uriBuilder ->
                uriBuilder
                    .queryParam("apikey", apiKey)
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
        val message = root["message"]?.asText() ?: "NO_MESSAGE"
        val resultNode = root["result"]

        if (status != "1") {
            val resultText = resultNode?.asText()
            throw EtherScanException(status, "$message: $resultText")
        }

        if (resultNode == null || !resultNode.isArray) {
            throw IllegalStateException("Unexpected Etherscan result format: $rawJson")
        }

        return mapper
            .readerForListOf(EtherscanTransaction::class.java)
            .readValue(resultNode)
    }

    fun printTransactionsDebug(transactions: List<EtherscanTransaction>) {
        println("Fetched ${transactions.size} transactions.")

        if (transactions.isEmpty()) {
            println("No transactions found.")
            return
        }

        transactions.forEachIndexed { index, tx ->
            val prettyJson = mapper
                .writerWithDefaultPrettyPrinter()
                .writeValueAsString(tx)

            println("===== Transaction #$index =====")
            println(prettyJson)
        }
    }
}
