package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.docker.AnvilContainerManager
import de.orchestrator.gas_optimizer_orchestrator.web.service.DeployService
import de.orchestrator.gas_optimizer_orchestrator.web.service.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.web.service.AnvilService
import de.orchestrator.gas_optimizer_orchestrator.web.service.InteractionService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.web3j.abi.FunctionEncoder


@Configuration
class DemoDeployConfig(
    private val deployService: DeployService,
    private val interactionService: InteractionService,
    private val etherScanService: EtherScanService,
    private val anvilService: AnvilService,
) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0x71cfeFd6b9208d2F639E78cfdcB3cf0739d2B9A6"

        val transactions = etherScanService.getTransactionsForAddress(target)
        val abi = etherScanService.getContractAbi(target)

        println("First Transaction:  ${transactions[0]}")
        println("This is the abi:  $abi")

        val interactions = interactionService.buildInteractions(
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

            val r = anvilService.sendInteraction(interaction)

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