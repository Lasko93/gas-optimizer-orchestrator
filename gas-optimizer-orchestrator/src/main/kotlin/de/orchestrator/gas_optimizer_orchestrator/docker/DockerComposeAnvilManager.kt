package de.orchestrator.gas_optimizer_orchestrator.docker

import de.orchestrator.gas_optimizer_orchestrator.utils.docker.DockerCommandExecutor
import de.orchestrator.gas_optimizer_orchestrator.utils.docker.SimpleHttpClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigInteger

/**
 * Manages Anvil blockchain instances via Docker Compose.
 *
 * Provides methods for:
 * - Starting/stopping Anvil forks
 * - Account impersonation and funding
 * - Storage and bytecode manipulation
 * - State queries (storage, code)
 */
@Service
class DockerComposeAnvilManager(
    private val dockerCommandExecutor: DockerCommandExecutor,
    private val dockerHttpClient: SimpleHttpClient
) {
    private val logger = LoggerFactory.getLogger(DockerComposeAnvilManager::class.java)

    companion object {
        private const val SERVICE_NAME = "anvil"
        private const val ALCHEMY_API_KEY_ENV = "ALCHEMY_API_KEY"
        private const val HTTP_OK = 200
        private const val SLOT_HEX_LENGTH = 64

        // Reentrancy guard constants
        private const val NOT_ENTERED_VALUE = "1"
    }

    val rpcUrl = "http://localhost:8545"

    // ============================================================
    // Fork Lifecycle
    // ============================================================

    /**
     * Executes a function within an Anvil fork context.
     *
     * @param blockNumber Block number to fork from
     * @param timeStamp Timestamp to set on the fork
     * @param fn Function to execute on the fork
     * @return Result of the function
     */
    fun <T> withAnvilFork(blockNumber: Long, timeStamp: String, fn: () -> T): T {
        startAnvilFork(blockNumber, timeStamp)
        dockerHttpClient.waitForRpc(rpcUrl)

        return try {
            fn()
        } finally {

        }
    }

    /**
     * Starts an Anvil fork at a specific block.
     */
    fun startAnvilFork(blockNumber: Long, timeStamp: String = "0") {
        logger.info("Starting Anvil fork at block {}", blockNumber)

        val env = buildForkEnvironment(
            enableFork = true,
            blockNumber = blockNumber,
            timeStamp = timeStamp
        )

        dockerCommandExecutor.composeUp(SERVICE_NAME, env = env, detached = true, forceRecreate = true)
        dockerHttpClient.waitForRpc(rpcUrl)

        logger.debug("Anvil fork ready at block {}", blockNumber)
    }


    private fun buildForkEnvironment(
        enableFork: Boolean,
        blockNumber: Long,
        timeStamp: String
    ): Map<String, String> = mapOf(
        "ENABLE_FORK" to enableFork.toString(),
        "ANVIL_FORK_BLOCK" to blockNumber.toString(),
        "ALCHEMY_API_KEY" to (System.getenv(ALCHEMY_API_KEY_ENV) ?: ""),
        "ANVIL_TIMESTAMP" to timeStamp
    )

    // ============================================================
    // Account Management
    // ============================================================

    /**
     * Impersonates an account, allowing transactions from it without a private key.
     */
    fun impersonateAccount(address: String) {
        validateAddress(address)

        logger.debug("Impersonating account: {}", address)

        executeRpcCall(
            method = "anvil_impersonateAccount",
            params = listOf(address),
            errorMessage = "Failed to impersonate account"
        )

        logger.trace("Impersonation active for {}", address)
    }

    /**
     * Sets the ETH balance of an account.
     */
    fun setBalance(address: String, balanceWei: BigInteger) {
        validateAddress(address)

        val hexBalance = "0x${balanceWei.toString(16)}"
        logger.debug("Setting balance for {}: {} wei", address, hexBalance)

        executeRpcCall(
            method = "anvil_setBalance",
            params = listOf(address, hexBalance),
            errorMessage = "Failed to set balance"
        )

        logger.trace("Balance set for {} to {}", address, hexBalance)
    }

    // ============================================================
    // Bytecode Manipulation
    // ============================================================

    /**
     * Replaces the runtime bytecode at an address.
     */
    fun replaceRuntimeBytecode(address: String, runtimeBytecode: String) {
        validateAddress(address)
        validateHexData(runtimeBytecode, "Runtime bytecode")

        logger.debug("Replacing bytecode at {} ({} bytes)", address, (runtimeBytecode.length - 2) / 2)

        executeRpcCall(
            method = "anvil_setCode",
            params = listOf(address, runtimeBytecode),
            errorMessage = "Failed to replace runtime bytecode"
        )

        logger.trace("Bytecode replaced at {}", address)
    }

    /**
     * Gets the bytecode at an address.
     */
    fun getCode(address: String): String {
        validateAddress(address)

        logger.trace("Getting code at {}", address)

        return executeRpcQuery(
            method = "eth_getCode",
            params = listOf(address, "latest"),
            errorMessage = "Failed to get code"
        )
    }

    // ============================================================
    // Storage Manipulation
    // ============================================================

    /**
     * Sets a storage slot value at an address.
     */
    fun setStorageAt(address: String, storageSlot: String, value: String) {
        validateAddress(address)
        validateHexData(storageSlot, "Storage slot")
        validateHexData(value, "Value")

        logger.debug("Setting storage at {} slot {} = {}", address, storageSlot, value)

        executeRpcCall(
            method = "anvil_setStorageAt",
            params = listOf(address, storageSlot, value),
            errorMessage = "Failed to set storage"
        )

        logger.trace("Storage set at {} slot {}", address, storageSlot)
    }

    /**
     * Gets a storage slot value at an address.
     */
    fun getStorageAt(address: String, storageSlot: String): String {
        validateAddress(address)
        validateHexData(storageSlot, "Storage slot")

        logger.trace("Getting storage at {} slot {}", address, storageSlot)

        return executeRpcQuery(
            method = "eth_getStorageAt",
            params = listOf(address, storageSlot, "latest"),
            errorMessage = "Failed to get storage"
        )
    }

    /**
     * Resets a reentrancy guard to NOT_ENTERED state.
     *
     * @param address Contract address
     * @param slotIndex Storage slot index where _status is stored
     */
    fun resetReentrancyGuard(address: String, slotIndex: Int = 0) {
        val slot = formatStorageSlot(slotIndex)
        val notEnteredValue = formatStorageValue(NOT_ENTERED_VALUE)

        logger.debug("Resetting reentrancy guard at {} slot {}", address, slotIndex)

        setStorageAt(address, slot, notEnteredValue)

        logger.info("Reset reentrancy guard for {} (slot {})", address, slotIndex)
    }

    // ============================================================
    // RPC Helpers
    // ============================================================

    private fun executeRpcCall(method: String, params: List<Any>, errorMessage: String) {
        val payload = buildJsonRpcPayload(method, params)
        val response = dockerHttpClient.postJson(rpcUrl, payload)

        if (response.code != HTTP_OK) {
            val fullMessage = "$errorMessage: HTTP ${response.code}\n${response.body}"
            logger.error(fullMessage)
            throw IllegalStateException(fullMessage)
        }
    }

    private fun executeRpcQuery(method: String, params: List<Any>, errorMessage: String): String {
        val payload = buildJsonRpcPayload(method, params)
        val response = dockerHttpClient.postJson(rpcUrl, payload)

        if (response.code != HTTP_OK) {
            val fullMessage = "$errorMessage: HTTP ${response.code}\n${response.body}"
            logger.error(fullMessage)
            throw IllegalStateException(fullMessage)
        }

        return parseResultFromResponse(response.body)
            ?: throw IllegalStateException("Could not parse result from response: ${response.body}")
    }

    private fun buildJsonRpcPayload(method: String, params: List<Any>): String {
        val paramsJson = params.joinToString(",") { "\"$it\"" }
        return """{"jsonrpc":"2.0","id":1,"method":"$method","params":[$paramsJson]}"""
    }

    private fun parseResultFromResponse(body: String): String? {
        val resultRegex = """"result"\s*:\s*"(0x[0-9a-fA-F]*)"""".toRegex()
        return resultRegex.find(body)?.groupValues?.get(1)
    }

    // ============================================================
    // Validation & Formatting
    // ============================================================

    private fun validateAddress(address: String) {
        require(address.startsWith("0x")) { "Address must start with 0x: $address" }
    }

    private fun validateHexData(data: String, name: String) {
        require(data.startsWith("0x")) { "$name must start with 0x: $data" }
    }

    private fun formatStorageSlot(slotIndex: Int): String {
        return "0x${slotIndex.toString(16).padStart(SLOT_HEX_LENGTH, '0')}"
    }

    private fun formatStorageValue(value: String): String {
        return "0x${value.padStart(SLOT_HEX_LENGTH, '0')}"
    }
}