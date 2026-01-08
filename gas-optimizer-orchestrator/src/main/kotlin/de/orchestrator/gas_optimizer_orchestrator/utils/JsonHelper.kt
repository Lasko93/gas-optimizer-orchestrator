package de.orchestrator.gas_optimizer_orchestrator.utils

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

object JsonHelper {

    private val mapper = jacksonObjectMapper()

    fun extractRemappingsFromStandardJson(standardJson: String): List<String> {
        return try {
            val jsonNode = mapper.readTree(standardJson)
            val settings = jsonNode.get("settings")

            // Check for remappings in settings.remappings array
            val remappingsArray = settings?.get("remappings")
            if (remappingsArray?.isArray == true) {
                remappingsArray.map { it.asText() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("Warning: Could not extract remappings from standard JSON: ${e.message}")
            emptyList()
        }
    }
}