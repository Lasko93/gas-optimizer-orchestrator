package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyConstants
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyInfo
import de.orchestrator.gas_optimizer_orchestrator.model.ProxyType
import org.springframework.stereotype.Service

@Service
class ProxyDetectionService(
    private val anvilManager: DockerComposeAnvilManager
) {
    companion object {
        private val SLOT_TWO = "0x0000000000000000000000000000000000000000000000000000000000000002"
        private val ZERO_VALUE = "0x" + "0".repeat(64)
    }

    /**
     * Detect proxy type by checking various proxy patterns
     */
    fun detectProxyType(contractAddress: String): ProxyInfo {
        val cleanAddress = contractAddress.lowercase()

        // Check EIP-1967 implementation slot
        val implSlotValue = anvilManager.getStorageAt(cleanAddress, ProxyConstants.EIP1967_IMPLEMENTATION_SLOT)
        if (implSlotValue.isNotEmpty() && implSlotValue != ZERO_VALUE && implSlotValue != "0x0") {
            return extractEIP1967Info(cleanAddress, implSlotValue)
        }

        // Check beacon proxy pattern
        val beaconSlotValue = anvilManager.getStorageAt(cleanAddress, ProxyConstants.EIP1967_BEACON_SLOT)
        if (beaconSlotValue.isNotEmpty() && beaconSlotValue != ZERO_VALUE && beaconSlotValue != "0x0") {
            return extractBeaconInfo(cleanAddress, beaconSlotValue)
        }

        // Check Compound-style proxy (implementation in slot 2)
        val slotTwoValue = anvilManager.getStorageAt(cleanAddress, SLOT_TWO)
        if (isPotentialCompoundProxy(slotTwoValue)) {
            return extractCompoundInfo(cleanAddress, slotTwoValue)
        }

        return ProxyInfo(ProxyType.NONE)
    }

    private fun extractEIP1967Info(address: String, implValue: String): ProxyInfo {
        return try {
            val implAddress = extractAddressFromSlot(implValue)
            val adminAddress = extractAdminAddress(address)

            ProxyInfo(
                proxyType = ProxyType.EIP1967,
                implementationAddress = implAddress,
                adminAddress = adminAddress,
                needsDelegatecallHandling = true
            )
        } catch (e: Exception) {
            println("Error extracting EIP-1967 info: ${e.message}")
            ProxyInfo(ProxyType.UNKNOWN)
        }
    }

    private fun extractBeaconInfo(address: String, beaconValue: String): ProxyInfo {
        return try {
            val beaconAddress = extractAddressFromSlot(beaconValue)

            ProxyInfo(
                proxyType = ProxyType.BEACON,
                beaconAddress = beaconAddress,
                needsDelegatecallHandling = true
            )
        } catch (e: Exception) {
            println("Error extracting beacon info: ${e.message}")
            ProxyInfo(ProxyType.UNKNOWN)
        }
    }

    private fun isPotentialCompoundProxy(slotTwoValue: String): Boolean {
        if (slotTwoValue.length < 42) return false
        if (slotTwoValue == ZERO_VALUE || slotTwoValue == "0x0") return false
        return isValidEthereumAddress(slotTwoValue.substring(slotTwoValue.length - 40))
    }

    private fun extractCompoundInfo(address: String, slotTwoValue: String): ProxyInfo {
        return try {
            val implAddress = extractAddressFromSlot(slotTwoValue)

            ProxyInfo(
                proxyType = ProxyType.COMPOUND,
                implementationAddress = implAddress,
                needsDelegatecallHandling = true
            )
        } catch (e: Exception) {
            println("Error extracting Compound info: ${e.message}")
            ProxyInfo(ProxyType.UNKNOWN)
        }
    }

    private fun extractAddressFromSlot(slotValue: String): String {
        return if (slotValue.length >= 42) {
            "0x" + slotValue.substring(slotValue.length - 40).lowercase()
        } else {
            slotValue
        }
    }

    private fun extractAdminAddress(address: String): String? {
        return try {
            val adminValue = anvilManager.getStorageAt(address, ProxyConstants.EIP1967_ADMIN_SLOT)
            if (adminValue.isNotEmpty() && adminValue != ZERO_VALUE && adminValue != "0x0") {
                extractAddressFromSlot(adminValue)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isValidEthereumAddress(address: String): Boolean {
        if (address.length != 40) return false
        return try {
            for (char in address) {
                if (!char.isDigit() && !char.isLetter() && char !in 'a'..'f' && char !in 'A'..'F') {
                    return false
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isProxy(contractAddress: String): Boolean {
        return detectProxyType(contractAddress).proxyType != ProxyType.NONE
    }
}