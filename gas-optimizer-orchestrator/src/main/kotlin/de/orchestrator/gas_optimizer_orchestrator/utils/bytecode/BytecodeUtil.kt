package de.orchestrator.gas_optimizer_orchestrator.utils.bytecode

/**
 * Utilities for working with EVM bytecode.
 */
object BytecodeUtil {

    private const val HEX_PREFIX = "0x"
    private const val MIN_BYTECODE_LENGTH = 4

    // ============================================================
    // Bytecode Manipulation
    // ============================================================

    /**
     * Appends constructor arguments to creation bytecode.
     *
     * @param bytecode The creation bytecode (with or without 0x prefix)
     * @param constructorArgsHex The ABI-encoded constructor arguments
     * @return Combined bytecode with 0x prefix
     */
    fun appendConstructorArgs(bytecode: String, constructorArgsHex: String): String {
        val normalizedArgs = constructorArgsHex.trim()

        if (normalizedArgs.isBlank() || normalizedArgs == HEX_PREFIX) {
            return ensureHexPrefix(bytecode)
        }

        val bytecodeHex = removeHexPrefix(ensureHexPrefix(bytecode))
        val argsHex = removeHexPrefix(ensureHexPrefix(normalizedArgs))

        return "$HEX_PREFIX$bytecodeHex$argsHex"
    }

    // ============================================================
    // Validation
    // ============================================================

    /**
     * Validates that bytecode is properly formatted and non-empty.
     *
     * @throws IllegalArgumentException if bytecode is invalid
     */
    fun validateBytecode(bytecode: String) {
        require(bytecode.startsWith(HEX_PREFIX)) {
            "Bytecode must start with 0x"
        }
        require(bytecode.length > MIN_BYTECODE_LENGTH) {
            "Bytecode too short; contract does not exist."
        }
    }


    // ============================================================
    // Hex Utilities
    // ============================================================

    /**
     * Ensures a hex string has the 0x prefix.
     */
    fun ensureHexPrefix(hex: String): String {
        val trimmed = hex.trim()
        return if (trimmed.startsWith(HEX_PREFIX)) trimmed else "$HEX_PREFIX$trimmed"
    }

    /**
     * Removes the 0x prefix if present.
     */
    fun removeHexPrefix(hex: String): String {
        return hex.removePrefix(HEX_PREFIX)
    }

}