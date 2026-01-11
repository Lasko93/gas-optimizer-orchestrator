package de.orchestrator.gas_optimizer_orchestrator.model.etherscan

import java.math.BigInteger

data class FullTransaction(
    val hash: String,
    val from: String,
    val to: String?,          // null for contract creation
    val value: BigInteger?,
    val gas: BigInteger,
    val gasPrice: BigInteger,
    val input: String,        // bytecode + constructor args for deployments
    val nonce: BigInteger,
    val blockNumber: Long
)