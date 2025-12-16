package de.orchestrator.gas_optimizer_orchestrator.docker

import org.springframework.stereotype.Service
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@Service
class DockerHelper(
    private val composeFile: File = File("docker-compose.yml")
) {

    data class HttpResponse(val code: Int, val body: String)

    // ----------------------------
    // High-level docker compose
    // ----------------------------

    fun dockerComposeUp(
        service: String,
        env: Map<String, String> = emptyMap(),
        detached: Boolean = true,
        forceRecreate: Boolean = true
    ) {
        val args = mutableListOf("up")
        if (detached) args += "-d"
        if (forceRecreate) args += "--force-recreate"
        args += service

        dockerCompose(args, env)
    }

    fun dockerComposeExecBash(
        service: String,
        bashScript: String,
        env: Map<String, String> = emptyMap(),
        tty: Boolean = false
    ) {
        val args = mutableListOf("exec")
        if (!tty) args += "-T" // CI / non-interactive
        args += listOf(service, "bash", "-lc", bashScript)

        dockerCompose(args, env)
    }

    fun dockerCompose(
        composeArgs: List<String>,
        env: Map<String, String> = emptyMap()
    ) {
        runCommand(
            command = listOf(
                "docker", "compose",
                "-f", composeFile.absolutePath
            ) + composeArgs,
            environment = env
        )
    }

    // ----------------------------
    // Command executor
    // ----------------------------

    fun runCommand(command: List<String>, environment: Map<String, String> = emptyMap()) {
        val workDir = composeFile.absoluteFile.parentFile ?: File(".")
        val pb = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)

        pb.environment().putAll(environment)

        val process = pb.start()

        val out = StringBuilder()
        process.inputStream.bufferedReader().forEachLine { line ->
            println(line)
            out.appendLine(line)
        }

        val exit = process.waitFor()
        if (exit != 0) {
            throw IllegalStateException(
                "Command failed (exit=$exit): $command\n--- output ---\n$out"
            )
        }
    }

    // ----------------------------
    // HTTP / JSON-RPC helpers
    // ----------------------------

    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json")
    ): HttpResponse {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

        conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()

        return HttpResponse(code, body)
    }

    fun waitForRpc(
        rpcUrl: String,
        timeoutMs: Long = 10_000,
        intervalMs: Long = 300,
        method: String = "eth_blockNumber"
    ) {
        val start = System.currentTimeMillis()
        val body = """{"jsonrpc":"2.0","id":1,"method":"$method","params":[]}"""

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val resp = postJson(rpcUrl, body)
                if (resp.code == 200) return
            } catch (_: Exception) {
                // ignore and retry
            }
            Thread.sleep(intervalMs)
        }

        throw IllegalStateException("Anvil RPC did not become ready in $timeoutMs ms ($rpcUrl)")
    }
}
