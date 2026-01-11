package de.orchestrator.gas_optimizer_orchestrator.utils.docker

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.HttpURLConnection
import java.net.URL

/**
 * Simple HTTP client for JSON-RPC communication.
 */
@Component
class SimpleHttpClient {

    private val logger = LoggerFactory.getLogger(SimpleHttpClient::class.java)

    companion object {
        private const val DEFAULT_TIMEOUT_MS = 10_000L
        private const val DEFAULT_POLL_INTERVAL_MS = 300L
    }

    /**
     * HTTP response wrapper.
     */
    data class HttpResponse(
        val code: Int,
        val body: String
    ) {
        val isSuccess: Boolean get() = code in 200..299
    }

    // ============================================================
    // HTTP Operations
    // ============================================================

    /**
     * Sends a POST request with JSON body.
     */
    fun postJson(
        url: String,
        jsonBody: String,
        headers: Map<String, String> = mapOf("Content-Type" to "application/json")
    ): HttpResponse {
        logger.trace("POST {} with body: {}", url, jsonBody.take(100))

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }

        connection.outputStream.use { output ->
            output.write(jsonBody.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        val responseStream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        val responseBody = responseStream?.bufferedReader()?.readText().orEmpty()

        logger.trace("Response {}: {}", responseCode, responseBody.take(200))

        return HttpResponse(responseCode, responseBody)
    }

    // ============================================================
    // RPC Utilities
    // ============================================================

    /**
     * Waits for an RPC endpoint to become available.
     *
     * @param rpcUrl The RPC endpoint URL
     * @param timeoutMs Maximum time to wait
     * @param intervalMs Polling interval
     * @param method JSON-RPC method to test with
     * @throws IllegalStateException if timeout is reached
     */
    fun waitForRpc(
        rpcUrl: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        intervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        method: String = "eth_blockNumber"
    ) {
        logger.debug("Waiting for RPC at {}", rpcUrl)

        val startTime = System.currentTimeMillis()
        val requestBody = buildJsonRpcRequest(method)

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                val response = postJson(rpcUrl, requestBody)
                if (response.isSuccess) {
                    logger.debug("RPC ready at {}", rpcUrl)
                    return
                }
            } catch (e: Exception) {
                logger.trace("RPC not ready yet: {}", e.message)
            }
            Thread.sleep(intervalMs)
        }

        throw IllegalStateException("RPC did not become ready in $timeoutMs ms ($rpcUrl)")
    }


    private fun buildJsonRpcRequest(method: String, params: List<Any> = emptyList()): String {
        val paramsJson = if (params.isEmpty()) {
            "[]"
        } else {
            params.joinToString(",", "[", "]") { param ->
                when (param) {
                    is String -> "\"$param\""
                    is Number -> param.toString()
                    is Boolean -> param.toString()
                    else -> "\"$param\""
                }
            }
        }
        return """{"jsonrpc":"2.0","id":1,"method":"$method","params":$paramsJson}"""
    }
}