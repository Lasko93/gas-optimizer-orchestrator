package de.orchestrator.gas_optimizer_orchestrator.service.slither

import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.model.slither.SlitherReport
import de.orchestrator.gas_optimizer_orchestrator.utils.docker.DockerCommandExecutor
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class SlitherService(
    private val dockerCommandExecutor: DockerCommandExecutor,
    private val objectMapper: ObjectMapper,
    @Value("\${docker.compiler.service-name:compiler}") private val serviceName: String
) {

    companion object {
        private val GAS_DETECTORS = listOf(
            "constable-states",      // State variables that could be constant
            "external-function",     // Public functions that could be external
            "costly-loop",           // Costly operations in loops
            "cache-array-length",    // Array length not cached in loop
            "immutable-states",      // State variables that could be immutable
            "dead-code",             // Unused functions
            "unused-state",          // Unused state variables
        )
    }

    fun analyzeForGasOptimizations(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList()
    ): SlitherReport {
        return runAnalysis(
            solFileName = solFileName,
            solcVersion = solcVersion,
            remappings = remappings,
            detectors = GAS_DETECTORS
        )
    }

    private fun runAnalysis(
        solFileName: String,
        solcVersion: String,
        remappings: List<String>,
        detectors: List<String>
    ): SlitherReport {
        val script = SlitherScriptBuilder(solFileName, solcVersion, remappings, detectors).build()
        val rawOutput = executeInDocker(script)
        return processOutput(rawOutput)
    }

    private fun executeInDocker(script: String): String =
        dockerCommandExecutor.composeExecBashWithOutput(
            service = serviceName,
            bashScript = script,
            tty = false,
            failOnError = false
        )

    private fun processOutput(rawOutput: String): SlitherReport {
        val cleanedOutput = SlitherOutputCleaner.clean(rawOutput)

        return if (SlitherOutputCleaner.containsJson(cleanedOutput)) {
            SlitherOutputParser(objectMapper).parse(cleanedOutput)
        } else {
            SlitherReport.failure("Slither produced no JSON output. Raw: $cleanedOutput")
        }
    }
}