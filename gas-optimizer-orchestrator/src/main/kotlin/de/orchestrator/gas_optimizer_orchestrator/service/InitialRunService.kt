package de.orchestrator.gas_optimizer_orchestrator.service

import de.orchestrator.gas_optimizer_orchestrator.model.*
import de.orchestrator.gas_optimizer_orchestrator.utils.ReceiptUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.SignatureUtil
import org.springframework.stereotype.Service
import java.nio.file.Path

@Service
class InitialRunService(
    private val compilationPipeline: CompilationPipeline,
    private val deployService: DeployService,
    private val interactionCreationService: InteractionCreationService,
    private val forkReplayService: ForkReplayService
) {

    private val externalContractsDir: Path =
        Path.of("gas-optimizer-orchestrator/src/main/kotlin/de/orchestrator/gas_optimizer_orchestrator/externalContracts")

    fun runInitial(
        transactions: List<EtherscanTransaction>,
        srcMeta: ContractSourceCodeResult,
        abiJson: String
    ): InitialRunResult {

        // 1) Compile + get deploy bytecode
        val compiled = compilationPipeline.compileToTruffleAndGetDeployBytecode(
            srcMeta = srcMeta,
            externalContractsDir = externalContractsDir
        )

        // 2) Measure deployment gas on no-fork (not used for replay)
        val deployReceipt = deployService.deployRawBytecode(compiled.deployBytecode)
        val deploymentGasUsed = deployReceipt.gasUsed?.toLong() ?: 0L

        // 3) Build interactions against TARGET address (forked contract)
        val interactions = interactionCreationService.buildInteractions(
            abiJson = abiJson,
            contractAddress = srcMeta.address,
            transactions = transactions
        )

        // 4) Replay on fork + map to FunctionGasUsed
        val functionCalls = interactions.map { interaction ->
            val signature = SignatureUtil.signature(interaction.functionName, interaction.abiTypes)
            val outcome = forkReplayService.replayOnForkWithFallback(interaction)

            val receipt = outcome.receipt
            if (receipt != null) {
                val ok = ReceiptUtil.isSuccess(receipt)
                FunctionGasUsed(
                    functionName = interaction.functionName,
                    functionSignature = signature,
                    gasUsed = ReceiptUtil.gasUsedLong(receipt),
                    succeeded = ok,
                    txHash = receipt.transactionHash,
                    revertReason = if (ok) null else "status=${receipt.status}"
                )
            } else {
                FunctionGasUsed(
                    functionName = interaction.functionName,
                    functionSignature = signature,
                    gasUsed = 0L,
                    succeeded = false,
                    txHash = null,
                    revertReason = outcome.errorMessage
                )
            }
        }

        return InitialRunResult(
            contractName = srcMeta.contractName,
            contractAddress = srcMeta.address,
            compilerInfo = CompilerInfo(solcVersion = srcMeta.compilerVersion),
            gasProfile = GasProfile(
                deploymentGasUsed = deploymentGasUsed,
                functionCalls = functionCalls
            ),
            runContext = RunContext(
                chainId = 1L,
                rpcUrl = "http://localhost:8545"
            )
        )
    }
}
