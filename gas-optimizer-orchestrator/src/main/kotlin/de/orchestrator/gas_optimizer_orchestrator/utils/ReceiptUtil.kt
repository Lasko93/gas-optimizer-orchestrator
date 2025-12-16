package de.orchestrator.gas_optimizer_orchestrator.utils

import org.web3j.protocol.core.methods.response.TransactionReceipt

object ReceiptUtil {

    fun isSuccess(receipt: TransactionReceipt): Boolean {
        val s = receipt.status ?: return true
        return s == "0x1" || s == "1"
    }

    fun gasUsedLong(receipt: TransactionReceipt): Long =
        receipt.gasUsed?.toLong() ?: 0L
}
