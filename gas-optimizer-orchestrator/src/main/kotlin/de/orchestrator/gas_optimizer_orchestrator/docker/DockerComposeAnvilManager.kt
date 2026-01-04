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
}
