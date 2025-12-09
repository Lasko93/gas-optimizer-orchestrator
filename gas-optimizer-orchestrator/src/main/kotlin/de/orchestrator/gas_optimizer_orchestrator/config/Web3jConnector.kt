package de.orchestrator.gas_optimizer_orchestrator.config

import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.crypto.Credentials
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.methods.response.Web3ClientVersion
import org.web3j.protocol.http.HttpService
import org.web3j.tx.gas.DefaultGasProvider



@Configuration
@EnableConfigurationProperties(Web3Props::class)
class Web3Config(
    private val props: Web3Props
) {

    /** Web3j client (closes gracefully on shutdown) */
    @Bean(destroyMethod = "shutdown")
    fun web3(): Web3j = Web3j.build(HttpService(props.httpUrl))

    /** Optional credentials bean if a private key is provided */
    @Bean
    @ConditionalOnProperty(prefix = "web3", name = ["private-key"])
    fun credentials(): Credentials = Credentials.create(requireNotNull(props.privateKey))

    /** Gas provider you can inject where needed */
    @Bean
    fun defaultGasProvider(): DefaultGasProvider = DefaultGasProvider()

    /**
     * Verify the connection on startup (mirrors the sample’s “print client version”
     * and shows the loaded account if credentials are configured).
     */
    @Bean
    fun web3HealthCheck(
        web3j: Web3j,
        credentials: Credentials?
    ) = CommandLineRunner {
        try {
            val clientVersion: Web3ClientVersion = web3j.web3ClientVersion().send()
            println("Connected to Ethereum client version: ${clientVersion.web3ClientVersion}")
            if (credentials != null) {
                println("Loaded account address: ${credentials.address}")
            } else {
                println("No private key configured (read-only mode).")
            }
        } catch (e: Exception) {
            System.err.println("Failed to connect to Anvil node at ${props.httpUrl}: ${e.message}")
        }
    }
}
