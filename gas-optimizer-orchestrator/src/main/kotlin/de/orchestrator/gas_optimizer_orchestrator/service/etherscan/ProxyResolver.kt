package de.orchestrator.gas_optimizer_orchestrator.service.etherscan

import de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan.EtherscanConstants.DEFAULT_CHAIN_ID
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ProxyResolution
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Service responsible for detecting and resolving proxy contracts.
 *
 * This service handles the logic of determining whether a contract is a proxy
 * and fetching the implementation contract's source code if so.
 */
@Service
class ProxyResolver(
    private val etherScanService: EtherScanService
) {
    private val logger = LoggerFactory.getLogger(ProxyResolver::class.java)

    /**
     * Resolves a contract address, detecting proxies and fetching implementation details.
     *
     * @param address The contract address to resolve
     * @param chainId The blockchain chain ID
     * @return ProxyResolution containing proxy and implementation details
     */
    fun resolve(address: String, chainId: String = DEFAULT_CHAIN_ID): ProxyResolution {
        val sourceMeta = etherScanService.getContractSourceCode(address, chainId)

        return if (sourceMeta.isProxy && !sourceMeta.implementationAddress.isNullOrBlank()) {
            resolveAsProxy(address, sourceMeta, chainId)
        } else {
            resolveAsDirect(address, sourceMeta)
        }
    }

    private fun resolveAsProxy(
        proxyAddress: String,
        proxySourceMeta: ContractSourceCodeResult,
        chainId: String
    ): ProxyResolution {
        val implAddress = proxySourceMeta.implementationAddress!!

        logger.info("Proxy detected at {} → implementation: {}", proxyAddress, implAddress)

        val implSourceMeta = etherScanService.getContractSourceCode(implAddress, chainId)

        logger.debug(
            "Implementation details - name: {}, constructor args: '{}'",
            implSourceMeta.contractName,
            implSourceMeta.constructorArgumentsHex
        )

        return ProxyResolution(
            isProxy = true,
            proxyAddress = proxyAddress,
            proxySourceMeta = proxySourceMeta,
            implementationAddress = implAddress,
            implementationSourceMeta = implSourceMeta
        )
    }

    private fun resolveAsDirect(
        address: String,
        sourceMeta: ContractSourceCodeResult
    ): ProxyResolution {
        logger.debug("Direct contract at {}", address)

        return ProxyResolution(
            isProxy = false,
            proxyAddress = null,
            proxySourceMeta = null,
            implementationAddress = address,
            implementationSourceMeta = sourceMeta
        )
    }
}