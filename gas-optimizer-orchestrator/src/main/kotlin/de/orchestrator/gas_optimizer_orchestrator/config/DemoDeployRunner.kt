package de.orchestrator.gas_optimizer_orchestrator.config

import de.orchestrator.gas_optimizer_orchestrator.web.service.DeployService
import de.orchestrator.gas_optimizer_orchestrator.web.service.EtherScanService
import de.orchestrator.gas_optimizer_orchestrator.web.service.InteractionService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File
import java.math.BigInteger

@Configuration
class DemoDeployConfig(
    private val deployService: DeployService,
    private val interactionService: InteractionService,
    private val etherScanService: EtherScanService

) {

    @Bean
    fun demoDeployRunner() = CommandLineRunner {
        val artifactFile =
            File("C:\\Users\\laskevic\\IdeaProjects\\gas-optimizer-orchestrator\\gas-optimizer-orchestrator\\src\\main\\kotlin\\de\\orchestrator\\gas_optimizer_orchestrator\\externalContracts\\seadrop\\out\\SeaDrop.sol\\SeaDrop.json")
        /*val receipt = deployService.deployContractFromJson(artifactFile)

        println(
            """
        === Deployment Receipt ===
        Contract Address: ${receipt.contractAddress}
        Transaction Hash: ${receipt.transactionHash}
        Block Number:     ${receipt.blockNumber}
        Gas Used:         ${receipt.gasUsed}
        Status:           ${receipt.status}   // 0x1 = success
        """.trimIndent()
        )*/

        val transactionsOfMainnet = etherScanService.getTransactionsForAddress("0x00005EA00Ac477B1030CE78506496e8C2dE24bf5")
        val uniqueFunctionTxs = transactionsOfMainnet
            .filter { it.input != "0x" }
            .distinctBy { it.functionName }
            .sortedBy { it.timeStamp.toLong() }
        etherScanService.printTransactionsDebug(uniqueFunctionTxs)

        val report = interactionService.measureInteractionsFromAbiUsingMainnetTx(
            artifactFile = artifactFile,
            contractAddress = transactionsOfMainnet.first().contractAddress,
            uniqueFunctionTxs
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