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
    val proxyAddress: String? = null,
    val implementationAddress: String? = null,
    val implementationSlot: String? = null,
    val beaconAddress: String? = null,
    val beaconImplSlot: String? = null,
    val needsDelegatecallHandling: Boolean = false
)

object ProxyConstants {
    // EIP-1967 implementation slot: bytes32(uint256(keccak256('eip1967.proxy.implementation')) - 1)
    const val EIP1967_IMPLEMENTATION_SLOT = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc"
    // EIP-1967 beacon slot: bytes32(uint256(keccak256('eip1967.proxy.beacon')) - 1)
    const val EIP1967_BEACON_SLOT = "0xa3f0ad74e5423aebfd80d3ef4346578335a9a72aeaee59ff6cb3582b35133d50"
    // EIP-1967 admin slot: bytes32(uint256(keccak256('eip1967.proxy.admin')) - 1)

    const val COMPOUND_IMPL_SLOT = "0x0000000000000000000000000000000000000000000000000000000000000002"

    const val OZ_LEGACY_IMPL_SLOT = "0x7050c9e0f4ca769c69bd3a8ef740bc37934f8e2c036e5a723fd8ee048ed3f8c3"
}