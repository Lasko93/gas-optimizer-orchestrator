package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
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
    private val interactionCreationService: InteractionCreationService,
    private val etherScanService: EtherScanService,
    private val anvilInteractionService: AnvilInteractionService,
    private val dockerComposeAnvilManager: DockerComposeAnvilManager
) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {

        val target = "0x61577E532D850dC7D6a8A81bCB945c01ba33a457"

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

            println("Spinning up fork for block ${interaction.blockNumber}")

            //Sometimes requires the exact block and sometimes the previous --> implement Service that does both
            dockerComposeAnvilManager.withAnvilFork(interaction.blockNumber.toLong()-1) {
                val result = anvilInteractionService.sendInteraction(interaction)

                println(
                    """
                Function   : ${interaction.functionName}
                TxHash     : ${result.transactionHash}
                GasUsed    : ${result.gasUsed}
                Status     : ${result.status}
                Blocknumber: ${result.blockNumber}
            """.trimIndent()
                )
            }
        }
    }
}