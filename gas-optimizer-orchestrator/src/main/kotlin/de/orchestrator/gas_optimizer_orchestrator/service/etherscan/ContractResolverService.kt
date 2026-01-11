package de.orchestrator.gas_optimizer_orchestrator.service.etherscan

import de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan.EtherscanConstants.DEFAULT_CHAIN_ID
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractResolution
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ProxyResolution
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Service for resolving contract information, handling both direct and proxy contracts.
 *
 * This service orchestrates the resolution process by delegating to:
 * - [ProxyResolver] for proxy detection and implementation resolution
 * - [ContractDataAggregator] for fetching ABIs, transactions, and creation info
 */
@Service
class ContractResolverService(
    private val proxyResolver: ProxyResolver,
    private val contractDataAggregator: ContractDataAggregator
) {
    private val logger = LoggerFactory.getLogger(ContractResolverService::class.java)

    /**
     * Resolves a contract address, returning type-safe resolution result.
     *
     * @param targetAddress The contract address to resolve
     * @param chainId The blockchain chain ID (default: "1" for mainnet)
     * @return ContractResolution sealed class with all contract data
     */
    fun resolve(targetAddress: String, chainId: String = DEFAULT_CHAIN_ID): ContractResolution {
        logger.info("Resolving contract: {}", targetAddress)

        val proxyResolution = proxyResolver.resolve(targetAddress, chainId)

        return if (proxyResolution.isProxy) {
            buildProxyResolution(proxyResolution, chainId)
        } else {
            buildDirectResolution(proxyResolution, chainId)
        }
    }

    private fun buildProxyResolution(
        proxyResolution: ProxyResolution,
        chainId: String
    ): ContractResolution.ProxyContract {
        val proxyAddress = proxyResolution.proxyAddress!!
        val implAddress = proxyResolution.implementationAddress

        logger.debug("Building proxy resolution: {} → {}", proxyAddress, implAddress)

        val abiJson = contractDataAggregator.fetchAbi(
            primaryAddress = implAddress,
            fallbackAddress = proxyAddress,
            chainId = chainId
        )

        val contractData = contractDataAggregator.aggregateForProxyContract(
            proxyAddress = proxyAddress,
            implementationAddress = implAddress,
            chainId = chainId
        )

        return ContractResolution.ProxyContract(
            proxyAddress = proxyAddress,
            implementationAddress = implAddress,
            proxySourceMeta = proxyResolution.proxySourceMeta!!,
            implementationSourceMeta = proxyResolution.implementationSourceMeta,
            abiJson = abiJson,
            transactions = contractData.transactions,
            creationInfo = contractData.creationInfo,
            creationTransaction = contractData.creationTransaction
        )
    }

    private fun buildDirectResolution(
        proxyResolution: ProxyResolution,
        chainId: String
    ): ContractResolution.DirectContract {
        val address = proxyResolution.implementationAddress

        logger.debug("Building direct resolution: {}", address)

        val abiJson = contractDataAggregator.fetchAbi(address, chainId = chainId)
        val contractData = contractDataAggregator.aggregateForDirectContract(address, chainId)

        return ContractResolution.DirectContract(
            address = address,
            sourceMeta = proxyResolution.implementationSourceMeta,
            abiJson = abiJson,
            transactions = contractData.transactions,
            creationInfo = contractData.creationInfo,
            creationTransaction = contractData.creationTransaction
        )
    }
}