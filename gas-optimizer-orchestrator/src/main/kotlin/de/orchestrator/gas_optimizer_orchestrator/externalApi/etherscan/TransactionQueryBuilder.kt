package de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan

/**
 * DSL builder for constructing transaction queries.
 *
 * Usage:
 * ```
 * val query = TransactionQueryBuilder()
 *     .forAddress("0x...")
 *     .onChain("1")
 *     .fromBlock(1000000)
 *     .ascending()
 *     .build()
 * ```
 */
class TransactionQueryBuilder {
    private var address: String = ""
    private var chainId: String = EtherscanConstants.DEFAULT_CHAIN_ID
    private var startBlock: Long = EtherscanConstants.DEFAULT_START_BLOCK
    private var endBlock: Long = EtherscanConstants.DEFAULT_END_BLOCK
    private var page: Int = EtherscanConstants.DEFAULT_PAGE
    private var offset: Int = EtherscanConstants.DEFAULT_PAGE_SIZE
    private var sort: String = EtherscanConstants.DEFAULT_SORT

    fun forAddress(address: String) = apply { this.address = address }
    fun onChain(chainId: String) = apply { this.chainId = chainId }
    fun fromBlock(block: Long) = apply { this.startBlock = block }
    fun toBlock(block: Long) = apply { this.endBlock = block }
    fun page(page: Int) = apply { this.page = page }
    fun limit(limit: Int) = apply { this.offset = limit }
    fun ascending() = apply { this.sort = "asc" }
    fun descending() = apply { this.sort = "desc" }

    fun build(): TransactionQuery {
        require(address.isNotBlank()) { "Address must be specified" }
        return TransactionQuery(
            address = address,
            chainId = chainId,
            startBlock = startBlock,
            endBlock = endBlock,
            page = page,
            offset = offset,
            sort = sort
        )
    }
}

/**
 * Immutable query parameters for fetching transactions.
 */
data class TransactionQuery(
    val address: String,
    val chainId: String,
    val startBlock: Long,
    val endBlock: Long,
    val page: Int,
    val offset: Int,
    val sort: String
) {
    fun toParamMap(): Map<String, Any> = mapOf(
        "address" to address,
        "startblock" to startBlock,
        "endblock" to endBlock,
        "page" to page,
        "offset" to offset,
        "sort" to sort
    )
}

/**
 * DSL function for building transaction queries.
 */
fun transactionQuery(init: TransactionQueryBuilder.() -> Unit): TransactionQuery {
    return TransactionQueryBuilder().apply(init).build()
}