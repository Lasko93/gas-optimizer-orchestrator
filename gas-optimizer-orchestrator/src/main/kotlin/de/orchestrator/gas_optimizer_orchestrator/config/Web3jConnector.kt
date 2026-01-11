package de.orchestrator.gas_optimizer_orchestrator.config


import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.protocol.Web3j
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

    /** Gas provider you can inject where needed */
    @Bean
    fun defaultGasProvider(): DefaultGasProvider = DefaultGasProvider()
}
