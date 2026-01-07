package de.orchestrator.gas_optimizer_orchestrator.model

data class ResolvedContractInfo(
    val proxyAddress: String?,                    // null if not a proxy
    val implementationAddress: String,            // The actual contract to compile
    val proxySourceMeta: ContractSourceCodeResult?, // Proxy source (if proxy)
    val implementationSourceMeta: ContractSourceCodeResult, // Implementation source (to compile)
    val abiJson: String,                          // ABI for interactions (from implementation)
    val transactions: List<EtherscanTransaction>, // Transactions (from proxy or direct contract)
    val creationInfo: ContractCreationInfo,       // Creation info (implementation)
    val creationTransaction: FullTransaction,     // Creation tx (implementation)
    val isProxy: Boolean
) {
    /**
     * The address where transactions are sent (proxy address if proxy, otherwise implementation)
     */
    val interactionAddress: String
        get() = proxyAddress ?: implementationAddress

    /**
     * The source metadata to compile (always implementation)
     */
    val sourceToCompile: ContractSourceCodeResult
        get() = implementationSourceMeta

    /**
     * Constructor args from the implementation contract
     */
    val constructorArgsHex: String
        get() = implementationSourceMeta.constructorArgumentsHex

    /**
     * Contract name (from implementation)
     */
    val contractName: String
        get() = implementationSourceMeta.contractName
}