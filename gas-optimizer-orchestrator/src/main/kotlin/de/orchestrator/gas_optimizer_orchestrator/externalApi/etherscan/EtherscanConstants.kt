package de.orchestrator.gas_optimizer_orchestrator.externalApi.etherscan

/**
 * Constants used across Etherscan-related services.
 */
object EtherscanConstants {
    const val DEFAULT_CHAIN_ID = "1"
    const val DEFAULT_START_BLOCK = 0L
    const val DEFAULT_END_BLOCK = 9_999_999_999L
    const val DEFAULT_PAGE = 1
    const val DEFAULT_PAGE_SIZE = 500
    const val DEFAULT_SORT = "asc"

    // API modules
    const val MODULE_CONTRACT = "contract"
    const val MODULE_ACCOUNT = "account"
    const val MODULE_PROXY = "proxy"

    // API actions
    const val ACTION_GET_SOURCE_CODE = "getsourcecode"
    const val ACTION_GET_ABI = "getabi"
    const val ACTION_TX_LIST = "txlist"
    const val ACTION_GET_CONTRACT_CREATION = "getcontractcreation"
    const val ACTION_ETH_GET_TX_BY_HASH = "eth_getTransactionByHash"

    // Response indicators
    const val PROXY_INDICATOR = "1"
    const val OPTIMIZATION_ENABLED = "1"
    const val CONTRACT_NOT_VERIFIED_MESSAGE = "Contract source code not verified"
}