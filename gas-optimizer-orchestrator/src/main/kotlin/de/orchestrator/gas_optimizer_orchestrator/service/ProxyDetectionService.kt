package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyConstants
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyInfo
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyType
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import org.springframework.stereotype.Service
import java.math.BigInteger

/**
 * Service for detecting proxy contracts and managing proxy implementations.
 * Handles all proxy-specific logic including deployment and storage management.
 */
@Service
class ProxyDetectionService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService
) {
    companion object {
        private val SLOT_TWO = "0x0000000000000000000000000000000000000000000000000000000000000002"
        private val ZERO_VALUE = "0x" + "0".repeat(64)
    }

    /**
     * Detects if a contract is a proxy and what type it is.
     * Enriches the returned ProxyInfo with implementation slots and other proxy-specific data.
     */
    fun detectProxyType(contractAddress: String): ProxyInfo {
        val cleanAddress = contractAddress.lowercase()

        // Try EIP-1967 first (most common)
        val eip1967Result = checkEIP1967Pattern(cleanAddress)
        if (eip1967Result.proxyType != ProxyType.NONE) {
            return enrichProxyInfo(eip1967Result.proxyType, eip1967Result.implementationAddress)
        }

        // Try Beacon proxy
        val beaconResult = checkBeaconPattern(cleanAddress)
        if (beaconResult.proxyType != ProxyType.NONE) {
            return enrichProxyInfo(beaconResult.proxyType, null, beaconResult.beaconAddress)
        }

        // Try Compound-style proxy
        val compoundResult = checkCompoundPattern(cleanAddress)
        if (compoundResult.proxyType != ProxyType.NONE) {
            return enrichProxyInfo(compoundResult.proxyType, compoundResult.implementationAddress)
        }

        return ProxyInfo(ProxyType.NONE)
    }

    /**
     * Deploys new implementation contract and updates proxy to use it.
     * This is the main method that handles all proxy updates.
     */
    fun deployAndUpdateImplementation(
        proxyInfo: ProxyInfo,
        creationBytecode: String,
        constructorArgsHex: String,
        deployerAddress: String,
        deployerValue: String,
        gasPrice: String
    ): Result<String> {
        try {
            println("Deploying new implementation for ${proxyInfo.proxyType} proxy")

            // Deploy the new implementation contract
            val deployBytecode = BytecodeUtil.appendConstructorArgs(creationBytecode, constructorArgsHex)

            // Set up deployer for transaction
            anvilManager.impersonateAccount(deployerAddress)
            anvilManager.setBalance(deployerAddress, java.math.BigInteger("100000000000000000000"))

            val deployReceipt = anvilInteractionService.sendRawTransaction(
                from = deployerAddress,
                to = null,
                value = BigInteger(deployerValue.removePrefix("0x"), 16),
                gasLimit = anvilInteractionService.gasLimit(),
                gasPrice = BigInteger(gasPrice.removePrefix("0x"), 16),
                data = deployBytecode
            )

            val newImplAddress = deployReceipt.contractAddress
                ?: return Result.failure(IllegalStateException("No contract address from deployment"))

            println("Deployed new implementation at: $newImplAddress")

            // Update proxy to use the new implementation
            updateProxyImplementation(proxyInfo, newImplAddress)

            // Reset reentrancy guard on the new implementation
            resetReentrancyGuard(newImplAddress)

            return Result.success(newImplAddress)

        } catch (e: Exception) {
            return Result.failure(Exception("Failed to deploy and update implementation: ${e.message}", e))
        }
    }

    /**
     * Updates existing proxy to use new implementation without deploying.
     */
    private fun updateProxyImplementation(
        proxyInfo: ProxyInfo,
        newImplementationAddress: String
    ) {
        val implSlot = requireNotNull(proxyInfo.implementationSlot) {
            "No implementation slot defined for proxy type ${proxyInfo.proxyType}"
        }

        val proxyAddress = requireNotNull(proxyInfo.implementationAddress) {
            "Cannot update proxy without proxy address"
        }

        // Format the new implementation address (padded to 32 bytes).
        val implSlotValue = "0x" + "0".repeat(24) + newImplementationAddress.substring(2).lowercase()

        when (proxyInfo.proxyType) {
            ProxyType.EIP1967 -> {
                anvilManager.setStorageAt(proxyAddress, implSlot, implSlotValue)
                if (proxyInfo.beaconAddress != null) {
                    // It's a beacon proxy, update the beacon's implementation
                    val beaconSlotValue = "0x" + "0".repeat(24) + newImplementationAddress.substring(2).lowercase()
                    val beaconImplSlot = requireNotNull(proxyInfo.beaconImplSlot)
                    anvilManager.setStorageAt(proxyInfo.beaconAddress, beaconImplSlot, beaconSlotValue)
                    println("Updated beacon implementation at ${proxyInfo.beaconAddress}")
                }
                println("Updated EIP-1967 implementation slot for proxy $proxyAddress")
            }
            ProxyType.BEACON -> {
                require(proxyInfo.beaconAddress != null) { "Beacon proxy must have beacon address" }
                // Update beacon's implementation
                val beaconSlotValue = "0x" + "0".repeat(24) + newImplementationAddress.substring(2).lowercase()
                val beaconImplSlot = requireNotNull(proxyInfo.beaconImplSlot)
                anvilManager.setStorageAt(proxyInfo.beaconAddress, beaconImplSlot, beaconSlotValue)
                println("Updated beacon implementation at ${proxyInfo.beaconAddress}")
            }
            ProxyType.COMPOUND -> {
                anvilManager.setStorageAt(proxyAddress, implSlot, implSlotValue)
                println("Updated Compound implementation at slot 2 for proxy $proxyAddress")
            }
            else -> return
        }
    }

    /**
     * Resets reentrancy guard for an implementation contract.
     */
    fun resetReentrancyGuard(implementationAddress: String) {
        try {
            // Try common reentrancy guard slots
            for (slotIndex in 0..4) {
                try {
                    anvilManager.resetReentrancyGuard(implementationAddress, slotIndex)
                } catch (e: Exception) {
                    // Slot might not exist, which is OK
                }
            }
        } catch (e: Exception) {
            println("Warning: Could not reset reentrancy guard for $implementationAddress: ${e.message}")
        }
    }

    // ===== Proxy Type Detection Methods =====

    private fun enrichProxyInfo(proxyType: ProxyType, implementationAddress: String?, beaconAddress: String? = null): ProxyInfo {
        return when (proxyType) {
            ProxyType.EIP1967 -> ProxyInfo(
                proxyType = proxyType,
                implementationAddress = implementationAddress,
                implementationSlot = ProxyConstants.EIP1967_IMPLEMENTATION_SLOT,
                needsDelegatecallHandling = true
            )
            ProxyType.BEACON -> ProxyInfo(
                proxyType = proxyType,
                beaconAddress = beaconAddress,
                implementationAddress = implementationAddress,
                implementationSlot = ProxyConstants.EIP1967_IMPLEMENTATION_SLOT,
                beaconImplSlot = ProxyConstants.EIP1967_IMPLEMENTATION_SLOT,
                needsDelegatecallHandling = true
            )
            ProxyType.COMPOUND -> ProxyInfo(
                proxyType = proxyType,
                implementationAddress = implementationAddress,
                implementationSlot = SLOT_TWO,
                needsDelegatecallHandling = true
            )
            else -> ProxyInfo(ProxyType.NONE)
        }
    }

    data class CheckResult(
        val proxyType: ProxyType,
        val implementationAddress: String? = null,
        val beaconAddress: String? = null
    )

    private fun checkEIP1967Pattern(address: String): CheckResult {
        val implSlotValue = anvilManager.getStorageAt(address, ProxyConstants.EIP1967_IMPLEMENTATION_SLOT)
        return if (implSlotValue.isNotEmpty() && implSlotValue != ZERO_VALUE && implSlotValue != "0x0") {
            val implAddress = extractAddressFromSlot(implSlotValue)
            CheckResult(ProxyType.EIP1967, implementationAddress = implAddress)
        } else {
            CheckResult(ProxyType.NONE)
        }
    }

    private fun checkBeaconPattern(address: String): CheckResult {
        val beaconSlotValue = anvilManager.getStorageAt(address, ProxyConstants.EIP1967_BEACON_SLOT)
        return if (beaconSlotValue.isNotEmpty() && beaconSlotValue != ZERO_VALUE && beaconSlotValue != "0x0") {
            val beaconAddress = extractAddressFromSlot(beaconSlotValue)
            CheckResult(ProxyType.BEACON, beaconAddress = beaconAddress)
        } else {
            CheckResult(ProxyType.NONE)
        }
    }

    private fun checkCompoundPattern(address: String): CheckResult {
        val slotTwoValue = anvilManager.getStorageAt(address, SLOT_TWO)
        return if (isPotentialCompoundProxy(slotTwoValue)) {
            val implAddress = extractAddressFromSlot(slotTwoValue)
            CheckResult(ProxyType.COMPOUND, implementationAddress = implAddress)
        } else {
            CheckResult(ProxyType.NONE)
        }
    }

    private fun extractAddressFromSlot(slotValue: String): String? {
        if (slotValue.length < 42) return null
        return "0x" + slotValue.substring(slotValue.length - 40).lowercase()
    }

    private fun isPotentialCompoundProxy(slotValue: String): Boolean {
        if (slotValue.length < 42) return false
        if (slotValue == ZERO_VALUE || slotValue == "0x0") return false
        return isValidEthereumAddress(slotValue.substring(slotValue.length - 40))
    }

    private fun isValidEthereumAddress(address: String): Boolean {
        if (address.length != 40) return false
        return try {
            address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        } catch (e: Exception) {
            false
        }
    }

    fun isProxy(contractAddress: String): Boolean {
        return detectProxyType(contractAddress).proxyType != ProxyType.NONE
    }
}