package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyConstants
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyInfo
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyType
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import org.springframework.stereotype.Service
import java.math.BigInteger

@Service
class ProxyDetectionService(
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilInteractionService: AnvilInteractionService
) {
    companion object {
        private const val ZERO_VALUE = "0x0000000000000000000000000000000000000000000000000000000000000000"
        private const val ZERO_SHORT = "0x0"

        // Compound uses slot 2 for implementation
        private const val COMPOUND_IMPL_SLOT = "0x0000000000000000000000000000000000000000000000000000000000000002"

        // Maximum bytecode size for a proxy (proxies are small, typically < 1KB)
        private const val MAX_PROXY_BYTECODE_SIZE = 2000

        // Minimum implementation bytecode size (should have actual code)
        private const val MIN_IMPLEMENTATION_SIZE = 100

        // DELEGATECALL opcode
        private const val DELEGATECALL_OPCODE = "f4"
    }

    /**
     * Detects if a contract is a proxy and what type it is.
     * Uses multiple heuristics to avoid false positives.
     */
    fun detectProxyType(contractAddress: String): ProxyInfo {
        val cleanAddress = cleanAddress(contractAddress)

        // Get contract bytecode for analysis
        val contractBytecode = try {
            anvilManager.getCode(cleanAddress)
        } catch (e: Exception) {
            println("⚠️ Could not fetch bytecode for $cleanAddress: ${e.message}")
            return ProxyInfo(ProxyType.NONE)
        }

        // If contract has no code, it's not a proxy
        if (isEmptyBytecode(contractBytecode)) {
            return ProxyInfo(ProxyType.NONE)
        }

        // Try EIP-1967 first (most common and most reliable)
        val eip1967Result = checkEIP1967Pattern(cleanAddress, contractBytecode)
        if (eip1967Result.proxyType != ProxyType.NONE) {
            println("✅ Detected EIP-1967 proxy at $cleanAddress -> ${eip1967Result.implementationAddress}")
            return enrichProxyInfo(
                proxyType = eip1967Result.proxyType,
                proxyAddress = cleanAddress,
                implementationAddress = eip1967Result.implementationAddress
            )
        }

        // Try Beacon proxy
        val beaconResult = checkBeaconPattern(cleanAddress, contractBytecode)
        if (beaconResult.proxyType != ProxyType.NONE) {
            println("✅ Detected Beacon proxy at $cleanAddress, beacon: ${beaconResult.beaconAddress}")
            return enrichProxyInfo(
                proxyType = beaconResult.proxyType,
                proxyAddress = cleanAddress,
                implementationAddress = beaconResult.implementationAddress,
                beaconAddress = beaconResult.beaconAddress
            )
        }

        // Try Compound-style proxy (with strict validation)
        val compoundResult = checkCompoundPattern(cleanAddress, contractBytecode)
        if (compoundResult.proxyType != ProxyType.NONE) {
            println("✅ Detected Compound proxy at $cleanAddress -> ${compoundResult.implementationAddress}")
            return enrichProxyInfo(
                proxyType = compoundResult.proxyType,
                proxyAddress = cleanAddress,
                implementationAddress = compoundResult.implementationAddress
            )
        }

        // Not a recognized proxy pattern
        return ProxyInfo(ProxyType.NONE)
    }

    /**
     * Check for EIP-1967 proxy pattern.
     * This is the most reliable pattern as it uses a specific storage slot.
     */
    private fun checkEIP1967Pattern(address: String, bytecode: String): CheckResult {
        val implSlotValue = getStorageSafe(address, ProxyConstants.EIP1967_IMPLEMENTATION_SLOT)

        if (isEmptySlot(implSlotValue)) {
            return CheckResult(ProxyType.NONE)
        }

        val implAddress = extractAddressFromSlot(implSlotValue)
            ?: return CheckResult(ProxyType.NONE)

        // Validate that implementation actually has code
        if (!hasCode(implAddress)) {
            println("⚠️ EIP-1967 slot points to address without code: $implAddress")
            return CheckResult(ProxyType.NONE)
        }

        return CheckResult(ProxyType.EIP1967, implementationAddress = implAddress)
    }

    /**
     * Check for Beacon proxy pattern.
     */
    private fun checkBeaconPattern(address: String, bytecode: String): CheckResult {
        val beaconSlotValue = getStorageSafe(address, ProxyConstants.EIP1967_BEACON_SLOT)

        if (isEmptySlot(beaconSlotValue)) {
            return CheckResult(ProxyType.NONE)
        }

        val beaconAddress = extractAddressFromSlot(beaconSlotValue)
            ?: return CheckResult(ProxyType.NONE)

        // Validate beacon has code
        if (!hasCode(beaconAddress)) {
            println("⚠️ Beacon slot points to address without code: $beaconAddress")
            return CheckResult(ProxyType.NONE)
        }

        // Try to get implementation from beacon
        val beaconImplSlot = getStorageSafe(beaconAddress, ProxyConstants.EIP1967_IMPLEMENTATION_SLOT)
        val implAddress = if (!isEmptySlot(beaconImplSlot)) {
            extractAddressFromSlot(beaconImplSlot)
        } else {
            null
        }

        return CheckResult(ProxyType.BEACON, beaconAddress = beaconAddress, implementationAddress = implAddress)
    }

    /**
     * Check for Compound-style proxy pattern.
     * This pattern stores implementation address in slot 2.
     *
     * IMPORTANT: This has high false-positive potential, so we apply strict validation:
     * 1. Contract bytecode must be small (proxies are minimal)
     * 2. Contract must contain DELEGATECALL opcode
     * 3. Implementation address must have code
     * 4. Implementation must be larger than proxy
     */
    private fun checkCompoundPattern(address: String, bytecode: String): CheckResult {
        val slotTwoValue = getStorageSafe(address, COMPOUND_IMPL_SLOT)

        if (isEmptySlot(slotTwoValue)) {
            return CheckResult(ProxyType.NONE)
        }

        // Check if value looks like a padded address
        if (!looksLikePaddedAddress(slotTwoValue)) {
            return CheckResult(ProxyType.NONE)
        }

        val potentialImplAddress = extractAddressFromSlot(slotTwoValue)
            ?: return CheckResult(ProxyType.NONE)

        // STRICT VALIDATION to avoid false positives like MasterChef

        // 1. Check proxy bytecode size - must be small
        val proxyCodeSize = getBytecodeSize(bytecode)
        if (proxyCodeSize > MAX_PROXY_BYTECODE_SIZE) {
            // Contract is too large to be a simple proxy
            return CheckResult(ProxyType.NONE)
        }

        // 2. Check for DELEGATECALL opcode in proxy bytecode
        if (!containsDelegatecall(bytecode)) {
            // No DELEGATECALL = not a proxy
            return CheckResult(ProxyType.NONE)
        }

        // 3. Check that implementation has code
        if (!hasCode(potentialImplAddress)) {
            return CheckResult(ProxyType.NONE)
        }

        // 4. Implementation should be larger than proxy
        val implBytecode = try {
            anvilManager.getCode(potentialImplAddress)
        } catch (e: Exception) {
            return CheckResult(ProxyType.NONE)
        }

        val implCodeSize = getBytecodeSize(implBytecode)
        if (implCodeSize <= proxyCodeSize) {
            // Implementation should have more code than the proxy
            return CheckResult(ProxyType.NONE)
        }

        // All checks passed - this is likely a Compound proxy
        return CheckResult(ProxyType.COMPOUND, implementationAddress = potentialImplAddress)
    }

    // ===== Deployment and Update Methods =====

    fun deployAndUpdateImplementation(
        proxyInfo: ProxyInfo,
        creationBytecode: String,
        constructorArgsHex: String,
        deployerAddress: String,
        deployerValue: String,
        gasPrice: String
    ): Result<String> {
        try {
            println("📦 Deploying new implementation for ${proxyInfo.proxyType} proxy")
            println("   Proxy address: ${proxyInfo.proxyAddress}")
            println("   Current implementation: ${proxyInfo.implementationAddress}")

            val deployBytecode = BytecodeUtil.appendConstructorArgs(creationBytecode, constructorArgsHex)

            anvilManager.impersonateAccount(deployerAddress)
            anvilManager.setBalance(deployerAddress, BigInteger("100000000000000000000"))

            val deployReceipt = anvilInteractionService.sendRawTransaction(
                from = deployerAddress,
                to = null,
                value = BigInteger.ZERO,
                gasLimit = anvilInteractionService.gasLimit(),
                gasPrice = BigInteger(gasPrice.removePrefix("0x"), 16),
                data = deployBytecode
            )

            val newImplAddress = deployReceipt.contractAddress
                ?: return Result.failure(IllegalStateException("No contract address from deployment"))

            println("   ✅ New implementation deployed at: $newImplAddress")

            updateProxyImplementation(proxyInfo, newImplAddress)
            proxyInfo.proxyAddress?.let { resetReentrancyGuard(it) }

            return Result.success(newImplAddress)

        } catch (e: Exception) {
            return Result.failure(Exception("Failed to deploy and update implementation: ${e.message}", e))
        }
    }

    private fun updateProxyImplementation(proxyInfo: ProxyInfo, newImplementationAddress: String) {
        val implSlot = requireNotNull(proxyInfo.implementationSlot) {
            "No implementation slot defined for proxy type ${proxyInfo.proxyType}"
        }

        val proxyAddress = requireNotNull(proxyInfo.proxyAddress) {
            "Cannot update proxy without proxy address"
        }

        val implSlotValue = "0x" + "0".repeat(24) + newImplementationAddress.removePrefix("0x").lowercase()

        when (proxyInfo.proxyType) {
            ProxyType.EIP1967 -> {
                anvilManager.setStorageAt(proxyAddress, implSlot, implSlotValue)
                println("   ✅ Updated EIP-1967 proxy $proxyAddress -> $newImplementationAddress")
            }
            ProxyType.BEACON -> {
                val beaconAddress = requireNotNull(proxyInfo.beaconAddress) {
                    "Beacon proxy must have beacon address"
                }
                val beaconImplSlot = requireNotNull(proxyInfo.beaconImplSlot)
                anvilManager.setStorageAt(beaconAddress, beaconImplSlot, implSlotValue)
                println("   ✅ Updated beacon $beaconAddress -> $newImplementationAddress")
            }
            ProxyType.COMPOUND -> {
                anvilManager.setStorageAt(proxyAddress, implSlot, implSlotValue)
                println("   ✅ Updated Compound proxy $proxyAddress -> $newImplementationAddress")
            }
            else -> {
                println("   ⚠️ Unknown proxy type ${proxyInfo.proxyType}, skipping update")
            }
        }
    }

    fun resetReentrancyGuard(contractAddress: String) {
        try {
            for (slotIndex in 0..4) {
                try {
                    anvilManager.resetReentrancyGuard(contractAddress, slotIndex)
                } catch (e: Exception) {
                    // Slot might not exist, which is OK
                }
            }
        } catch (e: Exception) {
            println("   ⚠️ Could not reset reentrancy guard for $contractAddress: ${e.message}")
        }
    }

    // ===== Helper Methods =====

    private fun enrichProxyInfo(
        proxyType: ProxyType,
        proxyAddress: String,
        implementationAddress: String?,
        beaconAddress: String? = null
    ): ProxyInfo {
        return when (proxyType) {
            ProxyType.EIP1967 -> ProxyInfo(
                proxyType = proxyType,
                proxyAddress = proxyAddress,
                implementationAddress = implementationAddress,
                implementationSlot = ProxyConstants.EIP1967_IMPLEMENTATION_SLOT,
                needsDelegatecallHandling = true
            )
            ProxyType.BEACON -> ProxyInfo(
                proxyType = proxyType,
                proxyAddress = proxyAddress,
                beaconAddress = beaconAddress,
                implementationAddress = implementationAddress,
                implementationSlot = ProxyConstants.EIP1967_IMPLEMENTATION_SLOT,
                beaconImplSlot = ProxyConstants.EIP1967_IMPLEMENTATION_SLOT,
                needsDelegatecallHandling = true
            )
            ProxyType.COMPOUND -> ProxyInfo(
                proxyType = proxyType,
                proxyAddress = proxyAddress,
                implementationAddress = implementationAddress,
                implementationSlot = COMPOUND_IMPL_SLOT,
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

    // ===== Utility Methods =====

    private fun cleanAddress(address: String): String {
        return address.lowercase().trim()
    }

    private fun getStorageSafe(address: String, slot: String): String {
        return try {
            anvilManager.getStorageAt(address, slot)
        } catch (e: Exception) {
            ""
        }
    }

    private fun isEmptySlot(slotValue: String): Boolean {
        if (slotValue.isBlank()) return true
        val normalized = slotValue.lowercase()
        return normalized == ZERO_VALUE.lowercase() ||
                normalized == ZERO_SHORT ||
                normalized == "0x"
    }

    private fun isEmptyBytecode(bytecode: String): Boolean {
        if (bytecode.isBlank()) return true
        val normalized = bytecode.lowercase()
        return normalized == "0x" || normalized == "0x0" || normalized.length <= 4
    }

    private fun hasCode(address: String): Boolean {
        val code = try {
            anvilManager.getCode(address)
        } catch (e: Exception) {
            return false
        }
        return !isEmptyBytecode(code) && getBytecodeSize(code) >= MIN_IMPLEMENTATION_SIZE
    }

    private fun getBytecodeSize(bytecode: String): Int {
        val hex = bytecode.removePrefix("0x")
        return hex.length / 2
    }

    private fun containsDelegatecall(bytecode: String): Boolean {
        // DELEGATECALL opcode is 0xf4
        return bytecode.lowercase().removePrefix("0x").contains(DELEGATECALL_OPCODE)
    }

    private fun looksLikePaddedAddress(slotValue: String): Boolean {
        val normalized = slotValue.lowercase().removePrefix("0x").padStart(64, '0')

        // First 24 characters should be zeros (12 bytes of padding)
        val padding = normalized.substring(0, 24)
        if (padding != "0".repeat(24)) {
            return false
        }

        // Last 40 characters should be a valid address
        val addressPart = normalized.substring(24)
        return isValidEthereumAddress(addressPart)
    }

    private fun extractAddressFromSlot(slotValue: String): String? {
        val normalized = slotValue.lowercase().removePrefix("0x").padStart(64, '0')
        val addressPart = normalized.substring(normalized.length - 40)

        if (!isValidEthereumAddress(addressPart)) {
            return null
        }

        // Don't return zero address
        if (addressPart == "0".repeat(40)) {
            return null
        }

        return "0x$addressPart"
    }

    private fun isValidEthereumAddress(address: String): Boolean {
        if (address.length != 40) return false
        return address.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun isProxy(contractAddress: String): Boolean {
        return detectProxyType(contractAddress).proxyType != ProxyType.NONE
    }
}