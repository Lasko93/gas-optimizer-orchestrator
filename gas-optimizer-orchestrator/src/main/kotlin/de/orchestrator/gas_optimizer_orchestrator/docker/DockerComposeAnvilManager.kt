package de.orchestrator.gas_optimizer_orchestrator.docker

import org.springframework.stereotype.Service
import java.math.BigInteger

@Service
class DockerComposeAnvilManager(
    private val docker: DockerHelper
) {
    val rpcUrl = "http://localhost:8545"

    fun <T> withAnvilFork(blockNumber: Long,timeStamp: String, fn: () -> T): T {
        startAnvilFork(blockNumber, timeStamp)
        docker.waitForRpc(rpcUrl)
        return try {
            fn()
        } finally {
            // optional: stop/cleanup
        }
    }

    fun startAnvilFork(blockNumber: Long, timeStamp: String = "0") {
        val env = mapOf(
            "ENABLE_FORK" to "true",
            "ANVIL_FORK_BLOCK" to blockNumber.toString(),
            "ALCHEMY_API_KEY" to (System.getenv("ALCHEMY_API_KEY") ?: ""),
            "ANVIL_TIMESTAMP" to timeStamp
        )

        println("Starting Anvil fork at block $blockNumber")
        docker.dockerComposeUp("anvil", env = env, detached = true, forceRecreate = true)
        docker.waitForRpc(rpcUrl)
    }

    fun startAnvilNoFork(genesisTimestamp: String? = "0") {
        val env = mapOf(
            "ENABLE_FORK" to "false",
            "ALCHEMY_API_KEY" to "",
            "ANVIL_FORK_BLOCK" to "0",
            "ANVIL_TIMESTAMP" to genesisTimestamp.toString()
        )

        println("Starting Anvil WITHOUT fork (timestamp=${genesisTimestamp ?: "default"})")
        docker.dockerComposeUp("anvil", env = env, detached = true, forceRecreate = true)
        docker.waitForRpc(rpcUrl)
    }

    fun replaceRuntimeBytecode(address: String, runtimeBytecode: String) {
        require(address.startsWith("0x")) { "Address must start with 0x" }
        require(runtimeBytecode.startsWith("0x")) { "Runtime bytecode must start with 0x" }

        val payload = """
            {
              "jsonrpc":"2.0",
              "id":1,
              "method":"anvil_setCode",
              "params":["$address","$runtimeBytecode"]
            }
        """.trimIndent()

        val resp = docker.postJson(rpcUrl, payload)

        if (resp.code != 200) {
            throw IllegalStateException("Failed to replace runtime bytecode: HTTP ${resp.code}\n${resp.body}")
        }

        println("✔ anvil_setCode succeeded for $address")
    }
    fun setBalance(address: String, balanceWei: BigInteger) {
        require(address.startsWith("0x")) { "Address must start with 0x" }

        val hexBalance = "0x" + balanceWei.toString(16)
        val payload = """
        {
          "jsonrpc":"2.0",
          "id":1,
          "method":"anvil_setBalance",
          "params":["$address","$hexBalance"]
        }
    """.trimIndent()

        val resp = docker.postJson(rpcUrl, payload)

        if (resp.code != 200) {
            throw IllegalStateException("Failed to set balance: HTTP ${resp.code}\n${resp.body}")
        }

        println("✔ anvil_setBalance succeeded for $address ($hexBalance wei)")
    }

    fun impersonateAccount(address: String) {
        require(address.startsWith("0x")) { "Address must start with 0x" }

        val payload = """
        {
          "jsonrpc":"2.0",
          "id":1,
          "method":"anvil_impersonateAccount",
          "params":["$address"]
        }
    """.trimIndent()

        val resp = docker.postJson(rpcUrl, payload)

        if (resp.code != 200) {
            throw IllegalStateException("Failed to impersonate account: HTTP ${resp.code}\n${resp.body}")
        }

        println("✔ Impersonating $address")
    }

    fun setStorageAt(address: String, storageSlot: String, value: String) {
        require(address.startsWith("0x")) { "Address must start with 0x" }
        require(storageSlot.startsWith("0x")) { "Storage slot must start with 0x" }
        require(value.startsWith("0x")) { "Value must start with 0x" }

        val payload = """
        {
          "jsonrpc":"2.0",
          "id":1,
          "method":"anvil_setStorageAt",
          "params":["$address","$storageSlot","$value"]
        }
    """.trimIndent()

        val resp = docker.postJson(rpcUrl, payload)

        if (resp.code != 200) {
            throw IllegalStateException("Failed to set storage: HTTP ${resp.code}\n${resp.body}")
        }

        println("✔ anvil_setStorageAt succeeded for $address at slot $storageSlot")
    }

    fun resetReentrancyGuard(address: String, slotIndex: Int = 0) {
        // Reentrancy guards typically use value 1 for NOT_ENTERED
        // The slot index depends on where the _status variable is declared in the contract
        val slot = "0x" + slotIndex.toString(16).padStart(64, '0')
        val notEnteredValue = "0x" + "1".padStart(64, '0')

        setStorageAt(address, slot, notEnteredValue)
        println("✔ Reset reentrancy guard for $address (slot $slotIndex)")
    }

    fun getStorageAt(address: String, storageSlot: String): String {
        require(address.startsWith("0x")) { "Address must start with 0x" }
        require(storageSlot.startsWith("0x")) { "Storage slot must start with 0x" }

        val payload = """
        {
          "jsonrpc":"2.0",
          "id":1,
          "method":"eth_getStorageAt",
          "params":["$address","$storageSlot","latest"]
        }
    """.trimIndent()

        val resp = docker.postJson(rpcUrl, payload)

        if (resp.code != 200) {
            throw IllegalStateException("Failed to get storage: HTTP ${resp.code}\n${resp.body}")
        }

        // Parse JSON response to extract result
        // Response format: {"jsonrpc":"2.0","id":1,"result":"0x..."}
        val resultRegex = """"result"\s*:\s*"(0x[0-9a-fA-F]+)"""".toRegex()
        val match = resultRegex.find(resp.body)
            ?: throw IllegalStateException("Could not parse storage value from response: ${resp.body}")

        return match.groupValues[1]
    }

    /**
     * Copy storage from source address to destination address.
     * Uses a heuristic to copy the most commonly used storage slots.
     *
     * @param maxSlots Maximum number of storage slots to copy (default: 100)
     */
    fun copyStorage(fromAddress: String, toAddress: String, maxSlots: Int = 100) {
        println("Copying storage from $fromAddress to $toAddress (checking $maxSlots slots)...")
        var copiedCount = 0

        for (i in 0 until maxSlots) {
            val slot = "0x" + i.toString(16).padStart(64, '0')
            val value = getStorageAt(fromAddress, slot)

            // Only copy non-zero values to reduce RPC calls
            if (value != "0x0" && value != "0x" + "0".padStart(64, '0')) {
                setStorageAt(toAddress, slot, value)
                copiedCount++
            }
        }

        println("✔ Copied $copiedCount storage slots from $fromAddress to $toAddress")
    }
}
