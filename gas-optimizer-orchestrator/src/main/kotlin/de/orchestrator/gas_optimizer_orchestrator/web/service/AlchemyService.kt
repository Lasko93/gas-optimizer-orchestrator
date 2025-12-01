package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class AlchemyService(
    @Value("\${alchemy.api-key}") private val apiKey: String,
    webClientBuilder: RestClient.Builder
) {

    private val mapper = jacksonObjectMapper()

    // Base URL for Ethereum mainnet (change chain if needed)
    private val baseUrl = "https://eth-mainnet.g.alchemy.com/v2/$apiKey"

    private val client: RestClient = webClientBuilder
        .baseUrl(baseUrl)
        .build()

    /**
     * Low-level RPC call → eth_getCode
     *
     * @return runtime bytecode starting with "0x", or "0x" if contract does not exist on-chain
     */
    fun getBytecode(address: String): String {
        val payload = """
            {
              "jsonrpc": "2.0",
              "method": "eth_getCode",
              "params": ["${address.trim()}", "latest"],
              "id": 1
            }
        """.trimIndent()

        val responseRaw = client.post()
            .body(payload)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null RPC response from Alchemy")

        val root: JsonNode = mapper.readTree(responseRaw)
        return root["result"]?.asText() ?: "0x"
    }

    /**
     * Optional: fetch transaction via eth_getTransactionByHash
     */
    fun getTransaction(txHash: String): JsonNode {
        val payload = """
            {
              "jsonrpc": "2.0",
              "method": "eth_getTransactionByHash",
              "params": ["$txHash"],
              "id": 1
            }
        """.trimIndent()

        val responseRaw = client.post()
            .body(payload)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null RPC response")

        return mapper.readTree(responseRaw)["result"]
    }

    /**
     * Optional: get logs, storage, etc.
     */
    fun callMethod(method: String, params: List<Any>): JsonNode {
        val payload = """
            {
              "jsonrpc": "2.0",
              "method": "$method",
              "params": ${mapper.writeValueAsString(params)},
              "id": 1
            }
        """.trimIndent()

        val raw = client.post().body(payload).retrieve().body(String::class.java)
            ?: throw IllegalStateException("Null RPC response")

        return mapper.readTree(raw)["result"]
    }
}
