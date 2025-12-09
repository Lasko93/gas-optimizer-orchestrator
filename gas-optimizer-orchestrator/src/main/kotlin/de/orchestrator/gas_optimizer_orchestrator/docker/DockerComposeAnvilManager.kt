package de.orchestrator.gas_optimizer_orchestrator.docker

import org.springframework.stereotype.Service
import java.io.File

@Service
class DockerComposeAnvilManager(
) {

    private val composeFile = File("docker-compose.yml")

    fun <T> withAnvilFork(
        blockNumber: Long,
        fn: () -> T
    ): T {
        startAnvilFork(blockNumber)
        val rpcUrl = "http://localhost:8545"

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
     * Start Anvil WITHOUT fork (ENABLE_FORK=false).
     */
    fun startAnvilNoFork() {
        val env = mapOf(
            "ENABLE_FORK" to "false",
            "ALCHEMY_API_KEY" to "",
            "ANVIL_FORK_BLOCK" to "0"
        )

        println("Starting Anvil WITHOUT fork")

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
