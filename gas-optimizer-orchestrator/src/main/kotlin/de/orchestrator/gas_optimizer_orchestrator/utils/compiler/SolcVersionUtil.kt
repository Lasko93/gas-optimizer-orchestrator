package de.orchestrator.gas_optimizer_orchestrator.utils.compiler

import org.slf4j.LoggerFactory

/**
 * Utilities for working with Solidity compiler versions.
 */
object SolcVersionUtil {

    private val logger = LoggerFactory.getLogger(SolcVersionUtil::class.java)

    private const val MIN_VIA_IR_VERSION = "0.8.13"

    // ============================================================
    // Version Normalization
    // ============================================================

    /**
     * Normalizes a solc version string from various formats.
     *
     * Examples:
     * - "v0.8.23+commit.f704f362" → "0.8.23"
     * - "0.8.23-nightly" → "0.8.23"
     * - "v0.8.23" → "0.8.23"
     */
    fun normalize(raw: String): String {
        return raw.trim()
            .removePrefix("v")
            .substringBefore("+")
            .substringBefore("-")
    }

    // ============================================================
    // Version Comparison
    // ============================================================

    /**
     * Compares two semantic version strings.
     *
     * @return Negative if v1 < v2, zero if equal, positive if v1 > v2
     */
    fun compare(v1: String, v2: String): Int {
        val parts1 = parseVersionParts(v1)
        val parts2 = parseVersionParts(v2)

        val maxLength = maxOf(parts1.size, parts2.size)

        for (i in 0 until maxLength) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1 - p2
        }

        return 0
    }

    /**
     * Checks if version1 is greater than or equal to version2.
     */
    fun isAtLeast(version: String, minVersion: String): Boolean {
        return compare(version, minVersion) >= 0
    }

    private fun parseVersionParts(version: String): List<Int> {
        return version
            .removePrefix("v")
            .split(".")
            .map { it.toIntOrNull() ?: 0 }
    }

    // ============================================================
    // Feature Detection
    // ============================================================

    /**
     * Checks if the given solc version supports the --via-ir flag.
     * --via-ir was introduced in Solidity 0.8.13.
     */
    fun supportsViaIr(solcVersion: String): Boolean {
        return isAtLeast(normalize(solcVersion), MIN_VIA_IR_VERSION)
    }

    /**
     * Configuration for via-ir compilation.
     */
    data class ViaIrConfig(
        val enabled: Boolean,
        val flag: String,
        val filePrefix: String
    )

    /**
     * Resolves via-ir compilation settings based on request and version support.
     *
     * @param requested Whether via-ir was requested
     * @param solcVersion The compiler version
     * @return Configuration with resolved settings
     */
    fun resolveViaIrConfig(requested: Boolean, solcVersion: String): ViaIrConfig {
        val supported = supportsViaIr(solcVersion)
        val enabled = requested && supported

        if (requested && !supported) {
            logger.warn(
                "--via-ir requested but solc {} does not support it (requires >= {}). " +
                        "Falling back to normal compilation.",
                solcVersion, MIN_VIA_IR_VERSION
            )
        }

        return ViaIrConfig(
            enabled = enabled,
            flag = if (enabled) "--via-ir" else "",
            filePrefix = if (enabled) "viair_runs" else "runs"
        )
    }
}