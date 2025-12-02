package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.service.DeployService
import de.orchestrator.gas_optimizer_orchestrator.externalApi.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.service.AnvilInteractionService
import de.orchestrator.gas_optimizer_orchestrator.service.InteractionCreationService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.abi.FunctionEncoder


@Configuration
class DemoDeployConfig(
    private val deployService: DeployService,
    private val interactionCreationService: InteractionCreationService,
    private val etherScanService: EtherScanService,
    private val anvilInteractionService: AnvilInteractionService,
) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0x71cfeFd6b9208d2F639E78cfdcB3cf0739d2B9A6"

        val transactions = etherScanService.getTransactionsForAddress(target)
        val abi = etherScanService.getContractAbi(target)

        println("First Transaction:  ${transactions[0]}")
        println("This is the abi:  $abi")

        val interactions = interactionCreationService.buildInteractions(
            abiJson = abi,
            contractAddress = target,
            transactions = transactions
        )

        println("All Interactions: $interactions")

        interactions.forEach { i ->
            val f = i.toWeb3jFunction()
            val encoded = FunctionEncoder.encode(f)
            println("Call: ${i.functionName} -> $encoded")
        }
        println("Found ${interactions.size} payable interactions")



        interactions.forEach { interaction ->

            println("Sending interaction: ${interaction.functionName}")

            val r = anvilInteractionService.sendInteraction(interaction)

            println(
                """
            Function   : ${interaction.functionName}
            TxHash     : ${r.transactionHash}
            GasUsed    : ${r.gasUsed}
            Status     : ${r.status}
        """.trimIndent()
            )
        }
    }
}