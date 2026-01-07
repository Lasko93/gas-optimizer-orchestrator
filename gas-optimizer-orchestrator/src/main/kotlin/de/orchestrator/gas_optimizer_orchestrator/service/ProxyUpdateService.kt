package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyConstants
import org.springframework.stereotype.Service

/**
 * Service responsible for updating proxy implementations on Anvil forks.
 * Auto-detects proxy pattern (EIP-1967, Beacon, Compound/Unitroller, etc.)
 */
@Service
class ProxyUpdateService(
    private val anvilManager: DockerComposeAnvilManager
) {

    /**
     * Updates proxy to point to a new implementation address.
     * Auto-detects which storage slot the proxy uses.
     */
    fun updateProxyImplementation(
        proxyAddress: String,
        newImplementationAddress: String
    ): Result<Unit> {
        return try {
            // Detect which pattern is used
            val detectionResult = detectProxyPattern(proxyAddress)
                ?: return Result.failure(
                    IllegalStateException("Could not detect proxy pattern for $proxyAddress")
                )

            println("   📍 Detected ${detectionResult.patternName} proxy pattern")

            when (detectionResult) {
                is ProxyPattern.Direct -> {
                    // EIP-1967, Compound, OZ Legacy - update proxy's storage directly
                    updateStorageSlot(proxyAddress, detectionResult.slot, newImplementationAddress)
                }
                is ProxyPattern.Beacon -> {
                    // Beacon proxy - update the beacon's implementation slot
                    println("   🔗 Updating beacon at ${detectionResult.beaconAddress}")
                    updateStorageSlot(detectionResult.beaconAddress, ProxyConstants.EIP1967_IMPLEMENTATION_SLOT, newImplementationAddress)
                }
            }

            println("   ✅ Updated proxy $proxyAddress -> $newImplementationAddress")

            // Reset reentrancy guards (common issue after bytecode swaps)
            resetReentrancyGuards(proxyAddress)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to update proxy implementation: ${e.message}", e))
        }
    }

    /**
     * Updates a storage slot and verifies the update.
     */
    private fun updateStorageSlot(address: String, slot: String, newImplementationAddress: String) {
        val implSlotValue = padAddressToSlot(newImplementationAddress)
        anvilManager.setStorageAt(address, slot, implSlotValue)

        // Verify the update
        val updatedValue = anvilManager.getStorageAt(address, slot)
        val expectedSuffix = newImplementationAddress.removePrefix("0x").lowercase()
        if (!updatedValue.lowercase().endsWith(expectedSuffix)) {
            throw IllegalStateException(
                "Slot update verification failed at $address. Expected suffix $expectedSuffix, got $updatedValue"
            )
        }
    }

    /**
     * Detects which proxy pattern is used.
     */
    private fun detectProxyPattern(proxyAddress: String): ProxyPattern? {
        // 1. Check EIP-1967 direct implementation slot (most common)
        val eip1967Value = getStorageSafe(proxyAddress, ProxyConstants.EIP1967_IMPLEMENTATION_SLOT)
        if (looksLikeValidImplementation(eip1967Value)) {
            return ProxyPattern.Direct(ProxyConstants.EIP1967_IMPLEMENTATION_SLOT, "EIP-1967")
        }

        // 2. Check EIP-1967 Beacon pattern
        val beaconSlotValue = getStorageSafe(proxyAddress, ProxyConstants.EIP1967_BEACON_SLOT)
        if (!isEmptySlot(beaconSlotValue)) {
            val beaconAddress = extractAddressFromSlot(beaconSlotValue)
            if (beaconAddress != null && hasCode(beaconAddress)) {
                // Verify beacon has an implementation slot
                val beaconImplValue = getStorageSafe(beaconAddress, ProxyConstants.EIP1967_IMPLEMENTATION_SLOT)
                if (looksLikeValidImplementation(beaconImplValue)) {
                    return ProxyPattern.Beacon(beaconAddress, "EIP-1967 Beacon")
                }
            }
        }

        // 3. Check Compound/Unitroller pattern (slot 2)
        val compoundValue = getStorageSafe(proxyAddress, ProxyConstants.COMPOUND_IMPL_SLOT)
        if (looksLikeValidImplementation(compoundValue)) {
            return ProxyPattern.Direct(ProxyConstants.COMPOUND_IMPL_SLOT, "Compound/Unitroller")
        }

        // 4. Check OpenZeppelin legacy pattern
        val ozLegacyValue = getStorageSafe(proxyAddress, ProxyConstants.OZ_LEGACY_IMPL_SLOT)
        if (looksLikeValidImplementation(ozLegacyValue)) {
            return ProxyPattern.Direct(ProxyConstants.OZ_LEGACY_IMPL_SLOT, "OpenZeppelin Legacy")
        }

        // Could not detect pattern
        println("   ⚠️ Could not detect proxy pattern for $proxyAddress")
        println("      EIP-1967 impl slot: $eip1967Value")
        println("      EIP-1967 beacon slot: $beaconSlotValue")
        println("      Compound slot: $compoundValue")
        println("      OZ Legacy slot: $ozLegacyValue")

        return null
    }

    /**
     * Represents detected proxy patterns.
     */
    private sealed class ProxyPattern(val patternName: String) {
        /** Direct proxy - implementation address stored in proxy's storage */
        class Direct(val slot: String, patternName: String) : ProxyPattern(patternName)

        /** Beacon proxy - implementation address stored in beacon contract's storage */
        class Beacon(val beaconAddress: String, patternName: String) : ProxyPattern(patternName)
    }

    /**
     * Checks if a slot value looks like a valid implementation address with code.
     */
    private fun looksLikeValidImplementation(slotValue: String): Boolean {
        if (isEmptySlot(slotValue)) return false
        val address = extractAddressFromSlot(slotValue) ?: return false
        return hasCode(address)
    }

    /**
     * Checks if an address has deployed code.
     */
    private fun hasCode(address: String): Boolean {
        return try {
            val code = anvilManager.getCode(address)
            !isEmptyBytecode(code)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Reset reentrancy guard slots (slots 0-1) which can get stuck after bytecode changes.
     */
    private fun resetReentrancyGuards(contractAddress: String) {
        // Only reset slots 0 and 1 - slot 2+ might be used for proxy implementation
        val safeSlots = listOf(0, 1)

        try {
            for (slotIndex in safeSlots) {
                try {
                    anvilManager.resetReentrancyGuard(contractAddress, slotIndex)
                } catch (_: Exception) {
                    // Slot might not exist, which is OK
                }
            }
        } catch (e: Exception) {
            println("   ⚠️ Could not reset reentrancy guard for $contractAddress: ${e.message}")
        }
    }

    // ===== Utility Methods =====

    private fun getStorageSafe(address: String, slot: String): String {
        return try {
            anvilManager.getStorageAt(address, slot)
        } catch (e: Exception) {
            ""
        }
    }

    private fun isEmptySlot(slotValue: String): Boolean {
        if (slotValue.isBlank()) return true
        val normalized = slotValue.lowercase().removePrefix("0x")
        return normalized.isBlank() || normalized.all { it == '0' }
    }

    private fun isEmptyBytecode(bytecode: String): Boolean {
        if (bytecode.isBlank()) return true
        val normalized = bytecode.lowercase()
        return normalized == "0x" || normalized == "0x0" || normalized.length <= 4
    }

    private fun extractAddressFromSlot(slotValue: String): String? {
        val normalized = slotValue.lowercase().removePrefix("0x").padStart(64, '0')
        val addressPart = normalized.substring(normalized.length - 40)

        // Don't return zero address
        if (addressPart == "0".repeat(40)) return null

        return "0x$addressPart"
    }

    /**
     * Pads an address to a 32-byte storage slot value.
     */
    private fun padAddressToSlot(address: String): String {
        val cleanAddress = address.removePrefix("0x").lowercase()
        return "0x" + "0".repeat(24) + cleanAddress
    }
}