package de.orchestrator.gas_optimizer_orchestrator.exceptions

class EtherScanException(
    val status: String,
    val etherScanMessage: String
) : RuntimeException("Etherscan error: status=$status, message=$etherScanMessage")