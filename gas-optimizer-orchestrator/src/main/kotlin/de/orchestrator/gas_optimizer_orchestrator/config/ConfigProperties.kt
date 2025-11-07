package de.orchestrator.gas_optimizer_orchestrator.config

import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties(prefix = "web3")
data class Web3Props(
    @field:NotBlank
    val httpUrl: String,
    val privateKey: String? = null
)