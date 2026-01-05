package de.orchestrator.gas_optimizer_orchestrator.model

enum class ProxyType {
    NONE,
    EIP1967,
    UUPS,
    BEACON,
    COMPOUND,
    UNKNOWN
}

data class ProxyInfo(
    val proxyType: ProxyType,
    val implementationAddress: String? = null,
    val beaconAddress: String? = null,
    val adminAddress: String? = null,
    val needsDelegatecallHandling: Boolean = true
)

object ProxyConstants {
    // EIP-1967 implementation slot: bytes32(uint256(keccak256('eip1967.proxy.implementation')) - 1)
    const val EIP1967_IMPLEMENTATION_SLOT = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc"
    // EIP-1967 beacon slot: bytes32(uint256(keccak256('eip1967.proxy.beacon')) - 1)
    const val EIP1967_BEACON_SLOT = "0xa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6cb3582b35133d50"
    // EIP-1967 admin slot: bytes32(uint256(keccak256('eip1967.proxy.admin')) - 1)
    const val EIP1967_ADMIN_SLOT = "0xb53127684a568b3173ae13b9f8a6016e243e63b6e8ee1178d6a717850b5d6103"
    // EIP-1967 implementation slot repeated for clarity
    const val BEACON_IMPLEMENTATION_SLOT = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc"
    // Compound-style proxy (implementation stored at slot 2, admin at slot 0)
    const val COMPOUND_IMPLEMENTATION_SLOT = "0x0000000000000000000000000000000000000000000000000000000000000002"
}