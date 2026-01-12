package de.orchestrator.gas_optimizer_orchestrator.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties(prefix = "gas-optimizer.paths")
data class GasOptimizerPathsProperties(
    val externalContractsDir: Path,
    val outputDir: String
)