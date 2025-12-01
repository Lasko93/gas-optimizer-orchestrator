package de.orchestrator.gas_optimizer_orchestrator.model

data class EtherscanTransaction(
    val blockNumber: String,
    val timeStamp: String,
    val hash: String,
    val nonce: String,
    val blockHash: String,
    val transactionIndex: String,
    val from: String,
    val to: String?,
    val value: String,
    val gas: String,
    val gasPrice: String,
    val isError: String,
    val txreceipt_status: String?,
    val input: String,
    val contractAddress: String,
    val cumulativeGasUsed: String,
    val gasUsed: String,
    val confirmations: String,
    val methodId: String? = null,
    val functionName: String? = null,
)

data class EtherscanTxListResponse(
    val status: String,
    val message: String,
    val result: List<EtherscanTransaction>
)