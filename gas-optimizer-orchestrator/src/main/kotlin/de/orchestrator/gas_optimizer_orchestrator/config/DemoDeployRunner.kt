package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.web.service.DeployService
import de.orchestrator.gas_optimizer_orchestrator.web.service.InteractionService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.math.BigInteger

@Configuration
class DemoDeployConfig(
    private val deployService: DeployService,
    private val interactionService: InteractionService
) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {
        val artifactFile =
            File("C:\\Users\\laskevic\\IdeaProjects\\gas-optimizer-orchestrator\\gas-optimizer-orchestrator\\src\\main\\kotlin\\de\\orchestrator\\gas_optimizer_orchestrator\\externalContracts\\vault-v2\\out\\VaultV2Factory.sol\\VaultV2Factory.json")
        val receipt = deployService.deployContractFromJson(artifactFile)

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

        val report = interactionService.measureInteractionsFromAbi(
            artifactFile = artifactFile,
            contractAddress = receipt.contractAddress
        )

        println("=== Interaction Results (successes=${report.successes.size}, errors=${report.errors.size}) ===")

        println("--- ✅ Successful Interactions ---")
        report.successes.forEach { r ->
            println(
                """
        Function:       ${r.functionSignature}
        File:           ${r.artifactFileName}
        Contract:       ${r.contractName}
        Gas Used:       ${r.gasUsed}
        Transaction:    ${r.txHash}
        ---------------------------------------
        """.trimIndent()
            )
        }

        println("--- ❌ Failed Interactions ---")
        report.errors.forEach { e ->
            println(
                """
        Function:       ${e.functionSignature}
        File:           ${e.artifactFileName}
        Contract:       ${e.contractName}
        Tx Hash:        ${e.txHash ?: "<none>"}
        Status:         ${e.status ?: "<no status>"}
        Gas Used:       ${e.gasUsed ?: BigInteger.valueOf(-1)}  
        Error:          ${e.message}
        ---------------------------------------
        """.trimIndent()
            )
        }
    }
}