package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.externalApi.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.model.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.ResolvedContractInfo
import org.springframework.stereotype.Service

@Service
class ContractResolverService(
    private val etherScanService: EtherScanService
) {

    fun resolveContract(targetAddress: String, chainId: String = "1"): ResolvedContractInfo {
        // Step 1: Fetch source code for target address
        val targetSourceMeta = etherScanService.getContractSourceCode(targetAddress, chainId)

        return if (targetSourceMeta.isProxy && !targetSourceMeta.implementationAddress.isNullOrBlank()) {
            resolveProxyContract(targetAddress, targetSourceMeta, chainId)
        } else {
            resolveDirectContract(targetAddress, targetSourceMeta, chainId)
        }
    }

    private fun resolveProxyContract(
        proxyAddress: String,
        proxySourceMeta: ContractSourceCodeResult,
        chainId: String
    ): ResolvedContractInfo {
        val implAddress = proxySourceMeta.implementationAddress!!

        println("🔗 Proxy detected at $proxyAddress")
        println("   → Implementation: $implAddress")

        val implSourceMeta = etherScanService.getContractSourceCode(implAddress, chainId)

        println("   → Implementation constructor args: '${implSourceMeta.constructorArgumentsHex}'")
        println("   → Implementation contract name: ${implSourceMeta.contractName}")

        val abiJson = try {
            etherScanService.getContractAbi(implAddress, chainId)
        } catch (e: Exception) {
            println("   ⚠️ Could not fetch implementation ABI, trying proxy ABI")
            etherScanService.getContractAbi(proxyAddress, chainId)
        }

        // Get implementation creation info FIRST
        val implCreationInfo = etherScanService.getContractCreationInfo(implAddress, chainId)
        val implCreationTx = etherScanService.getTransactionByHash(implCreationInfo.txHash, chainId)
        val implDeployBlock = implCreationTx.blockNumber

        println("   → Implementation deployed at block: $implDeployBlock")

        // Fetch transactions starting AFTER implementation deployment
        val transactions = etherScanService.getTransactionsForAddress(
            address = proxyAddress,
            chainId = chainId,
            startBlock = implDeployBlock + 1
        )

        println("   → Found ${transactions.size} transactions after implementation deployment")

        return ResolvedContractInfo(
            proxyAddress = proxyAddress,
            implementationAddress = implAddress,
            proxySourceMeta = proxySourceMeta,
            implementationSourceMeta = implSourceMeta,
            abiJson = abiJson,
            transactions = transactions,
            creationInfo = implCreationInfo,
            creationTransaction = implCreationTx,
            isProxy = true
        )
    }

    private fun resolveDirectContract(
        address: String,
        sourceMeta: ContractSourceCodeResult,
        chainId: String
    ): ResolvedContractInfo {
        val abiJson = etherScanService.getContractAbi(address, chainId)
        val transactions = etherScanService.getTransactionsForAddress(address, chainId)
        val creationInfo = etherScanService.getContractCreationInfo(address, chainId)
        val creationTx = etherScanService.getTransactionByHash(creationInfo.txHash, chainId)

        return ResolvedContractInfo(
            proxyAddress = null,
            implementationAddress = address,
            proxySourceMeta = null,
            implementationSourceMeta = sourceMeta,
            abiJson = abiJson,
            transactions = transactions,
            creationInfo = creationInfo,
            creationTransaction = creationTx,
            isProxy = false
        )
    }
}