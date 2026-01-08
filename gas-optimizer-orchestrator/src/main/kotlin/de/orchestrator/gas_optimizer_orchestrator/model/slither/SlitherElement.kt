package de.orchestrator.gas_optimizer_orchestrator.model.slither

/**
 * Represents a code element identified in a Slither finding.
 */
data class SlitherElement(
    val type: String,
    val name: String,
    val sourceMapping: SourceMapping? = null
)

/**
 * Source location information for a code element.
 */
data class SourceMapping(
    val filename: String,
    val start: Int,
    val length: Int,
    val lines: List<Int>
) {
    val lineRange: String
        get() = lines.take(3).joinToString(",")
}