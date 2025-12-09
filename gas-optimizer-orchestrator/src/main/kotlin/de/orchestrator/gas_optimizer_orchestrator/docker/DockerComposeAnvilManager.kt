package de.orchestrator.gas_optimizer_orchestrator.docker

import org.springframework.stereotype.Service
import java.io.File

@Service
class DockerComposeAnvilManager(
) {
    val rpcUrl = "http://localhost:8545"

    private val composeFile = File("docker-compose.yml")

    fun <T> withAnvilFork(
        blockNumber: Long,
        fn: () -> T
    ): T {
        startAnvilFork(blockNumber)


        waitForRpc(rpcUrl)

        return try {
            fn()
        } finally {
        }
    }


    fun startAnvilFork(blockNumber: Long) {
        val env = mapOf(
            "ENABLE_FORK" to "true",
            "ANVIL_FORK_BLOCK" to blockNumber.toString(),
            // Pass the API key through to Docker Compose if it exists in system env
            "ALCHEMY_API_KEY" to (System.getenv("ALCHEMY_API_KEY") ?: "")
        )

        println("Starting Anvil fork at block $blockNumber")

        runCommand(
            listOf(
                "docker", "compose",
                "-f", composeFile.absolutePath,
                "up", "-d", "--force-recreate",
                "anvil"
            ),
            env
        )
    }

    /**
     * Replace the runtime bytecode of a contract at the given address using Anvil's anvil_setCode.
     *
     * @param address  The target contract address (0x-prefixed).
     * @param optimizedRuntimeBytecode  MUST start with "0x". This is the optimized runtime bytecode.
     */
    fun replaceRuntimeBytecode(
        address: String,
        runtimeBytecode: String
    ) {
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

        val url = java.net.URL(rpcUrl)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")

        connection.outputStream.use { os ->
            os.write(payload.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val response = connection.inputStream.bufferedReader().readText()

        if (responseCode != 200) {
            throw IllegalStateException("Failed to replace runtime bytecode: HTTP $responseCode\n$response")
        }

        println("✔ anvil_setCode succeeded for $address")
    }

    /**
     * Start Anvil WITHOUT fork (ENABLE_FORK=false).
     */
    fun startAnvilNoFork(genesisTimestamp: String? = "0") {
        val env = mutableMapOf(
            "ENABLE_FORK" to "false",
            "ALCHEMY_API_KEY" to "",
            "ANVIL_FORK_BLOCK" to "0",
            "ANVIL_TIMESTAMP" to genesisTimestamp.toString(),
        )

        println("Starting Anvil WITHOUT fork (timestamp=${genesisTimestamp ?: "default"})")

        runCommand(
            listOf(
                "docker", "compose",
                "-f", composeFile.absolutePath,
                "up", "-d", "--force-recreate",
                "anvil"
            ),
            env
        )
        waitForRpc(rpcUrl)
    }

    // ---------------------------------------------------
    // Command executor
    // ---------------------------------------------------

    private fun runCommand(
        command: List<String>,
        environment: Map<String, String> = emptyMap()
    ) {
        val pb = ProcessBuilder(command)
            .directory(composeFile.parentFile)
            .redirectErrorStream(true)

        val env = pb.environment()
        environment.forEach { (k, v) -> env[k] = v }

        val process = pb.start()
        process.inputStream.bufferedReader().forEachLine { println(it) }

        val exit = process.waitFor()

        if (exit != 0) {
            throw IllegalStateException("Command failed: $command (exit=$exit)")
        }
    }

    // ---------------------------------------------------
    // Wait until RPC is ready
    // ---------------------------------------------------
    private fun waitForRpc(rpcUrl: String, timeoutMs: Long = 10_000) {
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val body = """
                    {"jsonrpc":"2.0","id":1,"method":"eth_blockNumber","params":[]}
                """.trimIndent()

                val conn = java.net.URL(rpcUrl).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }

                if (conn.responseCode == 200) return

            } catch (_: Exception) {
                Thread.sleep(300)
            }
        }

        throw IllegalStateException("Anvil RPC did not become ready in $timeoutMs ms")
    }
}
