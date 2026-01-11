package de.orchestrator.gas_optimizer_orchestrator.service.etherscan

import de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan.EtherscanConstants.DEFAULT_CHAIN_ID
import de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan.transactionQuery
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractCreationInfo
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.FullTransaction
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Aggregated contract data including creation info and transactions.
 */
data class ContractData(
    val creationInfo: ContractCreationInfo,
    val creationTransaction: FullTransaction,
    val transactions: List<EtherscanTransaction>
)

/**
 * Service responsible for aggregating contract-related data from Etherscan.
 *
 * This includes fetching ABIs, creation info, and transactions while
 * properly handling the differences between proxy and direct contracts.
 */
@Service
class ContractDataAggregator(
    private val etherScanService: EtherScanService
) {
    private val logger = LoggerFactory.getLogger(ContractDataAggregator::class.java)

    /**
     * Fetches the ABI for a contract, with fallback for proxy contracts.
     *
     * @param primaryAddress The primary address to try (implementation for proxies)
     * @param fallbackAddress Optional fallback address (proxy address)
     * @param chainId The blockchain chain ID
     * @return The ABI JSON string
     */
    fun fetchAbi(
        primaryAddress: String,
        fallbackAddress: String? = null,
        chainId: String = DEFAULT_CHAIN_ID
    ): String {
        return try {
            etherScanService.getContractAbi(primaryAddress, chainId)
        } catch (e: Exception) {
            if (fallbackAddress != null) {
                logger.warn(
                    "Could not fetch ABI for {}, trying fallback {}",
                    primaryAddress, fallbackAddress
                )
                etherScanService.getContractAbi(fallbackAddress, chainId)
            } else {
                throw e
            }
        }
    }

    /**
     * Aggregates all contract data for a direct (non-proxy) contract.
     */
    fun aggregateForDirectContract(
        address: String,
        chainId: String = DEFAULT_CHAIN_ID
    ): ContractData {
        logger.info("Aggregating data for direct contract: {}", address)

        val creationInfo = etherScanService.getContractCreationInfo(address, chainId)
        val creationTx = etherScanService.getTransactionByHash(creationInfo.txHash, chainId)

        val transactions = etherScanService.getTransactions(
            transactionQuery {
                forAddress(address)
                onChain(chainId)
                ascending()
            }
        )

        logger.debug("Found {} transactions for {}", transactions.size, address)

        return ContractData(
            creationInfo = creationInfo,
            creationTransaction = creationTx,
            transactions = transactions
        )
    }

    /**
     * Aggregates all contract data for a proxy contract.
     *
     * For proxy contracts, we fetch transactions starting AFTER the implementation
     * was deployed, to avoid including transactions from previous implementations.
     */
    fun aggregateForProxyContract(
        proxyAddress: String,
        implementationAddress: String,
        chainId: String = DEFAULT_CHAIN_ID
    ): ContractData {
        logger.info(
            "Aggregating data for proxy contract: {} → {}",
            proxyAddress, implementationAddress
        )

        // Get implementation creation info to determine start block
        val creationInfo = etherScanService.getContractCreationInfo(implementationAddress, chainId)
        val creationTx = etherScanService.getTransactionByHash(creationInfo.txHash, chainId)
        val deployBlock = creationTx.blockNumber

        logger.debug("Implementation deployed at block: {}", deployBlock)

        // Fetch transactions starting AFTER implementation deployment
        val transactions = etherScanService.getTransactions(
            transactionQuery {
                forAddress(proxyAddress)
                onChain(chainId)
                fromBlock(deployBlock + 1)
                ascending()
            }
        )

        logger.info(
            "Found {} transactions after implementation deployment",
            transactions.size
        )

        return ContractData(
            creationInfo = creationInfo,
            creationTransaction = creationTx,
            transactions = transactions
        )
    }
}