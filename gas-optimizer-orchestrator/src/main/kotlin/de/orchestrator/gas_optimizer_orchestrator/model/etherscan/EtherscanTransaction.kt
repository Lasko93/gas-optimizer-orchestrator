package de.orchestrator.gas_optimizer_orchestrator.model.etherscan

data class EtherscanTransaction(
    val blockNumber: String,
    val timeStamp: String,
    val hash: String,
    val nonce: String,
    val blockHash: String,
    val transactionIndex: String,
    var from: String,
    val to: String?,
    val value: String,
    val gas: String,
    var gasPrice: String,
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