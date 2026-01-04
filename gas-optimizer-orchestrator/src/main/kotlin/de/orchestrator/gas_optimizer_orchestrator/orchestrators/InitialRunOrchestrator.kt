package de.orchestrator.gas_optimizer_orchestrator.orchestrators

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.model.CompilerInfo
import de.orchestrator.gas_optimizer_orchestrator.model.ContractSourceCodeResult
import de.orchestrator.gas_optimizer_orchestrator.model.EtherscanTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.FullTransaction
import de.orchestrator.gas_optimizer_orchestrator.model.GasProfile
import de.orchestrator.gas_optimizer_orchestrator.model.GasTrackingResults
import de.orchestrator.gas_optimizer_orchestrator.model.RunContext
import de.orchestrator.gas_optimizer_orchestrator.service.AnvilInteractionService
import de.orchestrator.gas_optimizer_orchestrator.service.CompilationPipeline
import de.orchestrator.gas_optimizer_orchestrator.service.ForkReplayService
import de.orchestrator.gas_optimizer_orchestrator.service.InteractionCreationService
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.GasTrackingUtil.mapOutcomeToFunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.utils.SignatureUtil
import org.springframework.stereotype.Service

@Service
class InitialRunOrchestrator(
    private val compilationPipeline: CompilationPipeline,
    private val anvilService: AnvilInteractionService,
    private val interactionCreationService: InteractionCreationService,
    private val forkReplayService: ForkReplayService,
    private val paths: GasOptimizerPathsProperties
) {

    fun runInitial(
        transactions: List<EtherscanTransaction>,
        srcMeta: ContractSourceCodeResult,
        abiJson: String,
        creationTransaction: FullTransaction
    ): GasTrackingResults {

        // 1) Compile + get deploy bytecode
        val compiled = compilationPipeline.compileBaselineNoOptimize(
            srcMeta = srcMeta,
            externalContractsDir = paths.externalContractsDir,
            outFileName = srcMeta.contractName + ".json"
        )

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = compiled.creationBytecode,
            constructorArgsHex = srcMeta.constructorArgumentsHex
        )

        val deployReceipt = anvilService.deployOnFork(deployBytecode, creationTransaction)
        val deploymentGasUsed = deployReceipt.gasUsed?.toLong() ?: 0L

        // 2) Build interactions against TARGET address (forked contract)
        val interactions = interactionCreationService.buildInteractions(
            abiJson = abiJson,
            contractAddress = srcMeta.address,
            transactions = transactions
        )

        // 3) Replay on fork + map to FunctionGasUsed
        val functionCalls = interactions.map { interaction ->
            val signature = SignatureUtil.signature(interaction.functionName, interaction.abiTypes)
            val outcome = forkReplayService.replayOnForkAtPreviousBlock(interaction)
            mapOutcomeToFunctionGasUsed(interaction.functionName, signature, outcome)
        }

        return GasTrackingResults(
            contractName = srcMeta.contractName,
            contractAddress = srcMeta.address,
            creationBytecode = compiled.creationBytecode,
            runtimeBytecode = compiled.runtimeBytecode,
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