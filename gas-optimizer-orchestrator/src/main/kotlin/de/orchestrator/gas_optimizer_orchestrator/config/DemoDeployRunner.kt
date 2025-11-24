package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.web.service.ContractService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

@Configuration
class DemoDeployConfig(
    private val contractService: ContractService
) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {
        val artifactFile = File("C:\\Users\\laskevic\\IdeaProjects\\gas-optimizer-orchestrator\\gas-optimizer-orchestrator\\src\\main\\kotlin\\de\\orchestrator\\gas_optimizer_orchestrator\\externalContracts\\v2-foundry\\out\\AAVETokenAdapter.sol\\AAVETokenAdapter.json")
        val receipt = contractService.deployContractFromJson(artifactFile)

        println(
            """
        === Deployment Receipt ===
        Contract Address: ${receipt.contractAddress}
        Transaction Hash: ${receipt.transactionHash}
        Block Number:     ${receipt.blockNumber}
        Gas Used:         ${receipt.gasUsed}
        Status:           ${receipt.status}   // 0x1 = success
        """.trimIndent()
        )
    }
}
