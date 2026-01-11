package de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan

import com.fasterxml.jackson.databind.JsonNode
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractCreationInfo
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.FullTransaction
import de.orchestrator.gas_optimizer_orchestrator.utils.EtherScanHelper.normalizeAddress
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Service for interacting with the Etherscan API.
 *
 * Handles rate limiting, HTTP communication, and delegates response parsing
 * to [EtherscanResponseParser].
 */
@Service
class EtherScanService(
    @Value("\${etherscan.api-key}") private val apiKey: String,
    @Value("\${etherscan.base-url:https://api.etherscan.io/v2/api}") baseUrl: String,
    @Value("\${etherscan.rate-limit-ms:500}") private val rateLimitMs: Long,
    webClientBuilder: RestClient.Builder,
    private val responseParser: EtherscanResponseParser
) {
    private val logger = LoggerFactory.getLogger(EtherScanService::class.java)
    private val client: RestClient = webClientBuilder.baseUrl(baseUrl).build()

    private val rateLimitLock = ReentrantLock()
    private var lastRequestTime: Long = 0

    // ============================================================
    // Generic Request Executor
    // ============================================================

    /**
     * Executes an Etherscan API request with rate limiting.
     *
     * @param chainId The blockchain chain ID
     * @param module The API module (contract, account, proxy)
     * @param action The API action
     * @param params Additional query parameters
     * @param parser Function to parse the JSON response
     * @return The parsed result
     */
    private fun <T> executeRequest(
        chainId: String,
        module: String,
        action: String,
        params: Map<String, Any> = emptyMap(),
        parser: (JsonNode) -> T
    ): T {
        rateLimit()

        logger.debug("Executing Etherscan request: module={}, action={}, params={}", module, action, params)

        val rawJson = client.get()
            .uri { builder ->
                builder
                    .queryParam("apikey", apiKey)
                    .queryParam("chainid", chainId)
                    .queryParam("module", module)
                    .queryParam("action", action)
                params.forEach { (key, value) -> builder.queryParam(key, value) }
                builder.build()
            }
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Null response from Etherscan")

        val root = responseParser.parseJson(rawJson)
        return parser(root)
    }

    private fun rateLimit() {
        rateLimitLock.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestTime
            if (elapsed < rateLimitMs) {
                val sleepTime = rateLimitMs - elapsed
                logger.trace("Rate limiting: sleeping for {}ms", sleepTime)
                Thread.sleep(sleepTime)
            }
            lastRequestTime = System.currentTimeMillis()
        }
    }

    // ============================================================
    // Public API Methods
    // ============================================================

    /**
     * Fetches verified source code for a contract address.
     */
    fun getContractSourceCode(
        address: String,
        chainId: String = EtherscanConstants.DEFAULT_CHAIN_ID
    ): ContractSourceCodeResult {
        val trimmedAddress = address.trim()
        logger.info("Fetching source code for contract: {}", trimmedAddress)

        return executeRequest(
            chainId = chainId,
            module = EtherscanConstants.MODULE_CONTRACT,
            action = EtherscanConstants.ACTION_GET_SOURCE_CODE,
            params = mapOf("address" to trimmedAddress)
        ) { root ->
            val result = responseParser.parseContractSourceCode(root, trimmedAddress)

            if (result.isProxy && !result.implementationAddress.isNullOrBlank()) {
                logger.info("Proxy detected at {} → implementation: {}", trimmedAddress, result.implementationAddress)
            }

            result
        }
    }

    /**
     * Fetches transactions for an address using a query builder.
     */
    fun getTransactions(query: TransactionQuery): List<EtherscanTransaction> {
        val normalizedAddress = normalizeAddress(query.address)
        logger.info("Fetching transactions for address: {}", normalizedAddress)

        return executeRequest(
            chainId = query.chainId,
            module = EtherscanConstants.MODULE_ACCOUNT,
            action = EtherscanConstants.ACTION_TX_LIST,
            params = query.copy(address = normalizedAddress).toParamMap()
        ) { root ->
            val transactions = responseParser.parseTransactions(root)
            logger.debug("Found {} unique transactions", transactions.size)
            transactions
        }
    }

    /**
     * Fetches the ABI for a verified contract.
     */
    fun getContractAbi(
        address: String,
        chainId: String = EtherscanConstants.DEFAULT_CHAIN_ID
    ): String {
        val trimmedAddress = normalizeAddress(address)
        logger.info("Fetching ABI for contract: {}", trimmedAddress)

        return executeRequest(
            chainId = chainId,
            module = EtherscanConstants.MODULE_CONTRACT,
            action = EtherscanConstants.ACTION_GET_ABI,
            params = mapOf("address" to trimmedAddress)
        ) { root ->
            responseParser.parseAbi(root)
        }
    }

    /**
     * Fetches contract creation information.
     */
    fun getContractCreationInfo(
        contractAddress: String,
        chainId: String = EtherscanConstants.DEFAULT_CHAIN_ID
    ): ContractCreationInfo {
        val trimmedAddress = normalizeAddress(contractAddress)
        logger.info("Fetching creation info for contract: {}", trimmedAddress)

        return executeRequest(
            chainId = chainId,
            module = EtherscanConstants.MODULE_CONTRACT,
            action = EtherscanConstants.ACTION_GET_CONTRACT_CREATION,
            params = mapOf("contractaddresses" to trimmedAddress)
        ) { root ->
            responseParser.parseContractCreationInfo(root)
        }
    }

    /**
     * Fetches full transaction details by hash.
     */
    fun getTransactionByHash(
        txHash: String,
        chainId: String = EtherscanConstants.DEFAULT_CHAIN_ID
    ): FullTransaction {
        logger.info("Fetching transaction: {}", txHash)

        return executeRequest(
            chainId = chainId,
            module = EtherscanConstants.MODULE_PROXY,
            action = EtherscanConstants.ACTION_ETH_GET_TX_BY_HASH,
            params = mapOf("txhash" to txHash)
        ) { root ->
            responseParser.parseFullTransaction(root, txHash)
        }
    }
}