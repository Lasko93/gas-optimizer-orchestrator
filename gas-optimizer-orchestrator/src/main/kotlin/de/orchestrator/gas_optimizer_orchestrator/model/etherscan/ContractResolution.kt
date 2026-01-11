package de.orchestrator.gas_optimizer_orchestrator.model.etherscan

/**
 * Sealed class representing the result of contract resolution.
 *
 * This provides type-safe handling of proxy vs direct contract scenarios,
 * ensuring compile-time checking of all cases.
 */
sealed class ContractResolution {

    /**
     * Resolution result for a direct (non-proxy) contract.
     */
    data class DirectContract(
        val address: String,
        val sourceMeta: ContractSourceCodeResult,
        val abiJson: String,
        val transactions: List<EtherscanTransaction>,
        val creationInfo: ContractCreationInfo,
        val creationTransaction: FullTransaction
    ) : ContractResolution() {

        /**
         * Converts to the legacy ResolvedContractInfo format for backwards compatibility.
         */
        fun toResolvedContractInfo(): ResolvedContractInfo = ResolvedContractInfo(
            proxyAddress = null,
            implementationAddress = address,
            proxySourceMeta = null,
            implementationSourceMeta = sourceMeta,
            abiJson = abiJson,
            transactions = transactions,
            creationInfo = creationInfo,
            creationTransaction = creationTransaction,
            isProxy = false
        )
    }

    /**
     * Resolution result for a proxy contract with its implementation.
     */
    data class ProxyContract(
        val proxyAddress: String,
        val implementationAddress: String,
        val proxySourceMeta: ContractSourceCodeResult,
        val implementationSourceMeta: ContractSourceCodeResult,
        val abiJson: String,
        val transactions: List<EtherscanTransaction>,
        val creationInfo: ContractCreationInfo,
        val creationTransaction: FullTransaction
    ) : ContractResolution() {

        /**
         * Converts to the legacy ResolvedContractInfo format for backwards compatibility.
         */
        fun toResolvedContractInfo(): ResolvedContractInfo = ResolvedContractInfo(
            proxyAddress = proxyAddress,
            implementationAddress = implementationAddress,
            proxySourceMeta = proxySourceMeta,
            implementationSourceMeta = implementationSourceMeta,
            abiJson = abiJson,
            transactions = transactions,
            creationInfo = creationInfo,
            creationTransaction = creationTransaction,
            isProxy = true
        )
    }
}

/**
 * Extension function to convert any ContractResolution to ResolvedContractInfo.
 */
fun ContractResolution.toResolvedContractInfo(): ResolvedContractInfo = when (this) {
    is ContractResolution.DirectContract -> this.toResolvedContractInfo()
    is ContractResolution.ProxyContract -> this.toResolvedContractInfo()
}

/**
 * Extension properties for convenient access to common fields.
 */
val ContractResolution.effectiveAddress: String
    get() = when (this) {
        is ContractResolution.DirectContract -> address
        is ContractResolution.ProxyContract -> implementationAddress
    }

val ContractResolution.effectiveSourceMeta: ContractSourceCodeResult
    get() = when (this) {
        is ContractResolution.DirectContract -> sourceMeta
        is ContractResolution.ProxyContract -> implementationSourceMeta
    }

val ContractResolution.isProxy: Boolean
    get() = this is ContractResolution.ProxyContract