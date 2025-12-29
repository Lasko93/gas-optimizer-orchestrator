package de.orchestrator.gas_optimizer_orchestrator.model.slither

data class SlitherElement(
    val type: String,
    val name: String,
    val sourceMapping: SourceMapping? = null
)

data class SourceMapping(
    val filename: String,
    val start: Int,
    val length: Int,
    val lines: List<Int>
)