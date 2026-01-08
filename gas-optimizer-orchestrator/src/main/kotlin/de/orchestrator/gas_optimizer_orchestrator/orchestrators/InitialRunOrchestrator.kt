package de.orchestrator.gas_optimizer_orchestrator.orchestrators

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.model.CompilerInfo
import de.orchestrator.gas_optimizer_orchestrator.model.GasProfile
import de.orchestrator.gas_optimizer_orchestrator.model.GasTrackingResults
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ResolvedContractInfo
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

    fun runInitial(resolved: ResolvedContractInfo): GasTrackingResults {

        val srcMeta = resolved.sourceToCompile

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

        // Measure deployment gas on a separate fork
        val deployReceipt = anvilService.deployOnFork(deployBytecode, resolved.creationTransaction)
        val deploymentGasUsed = deployReceipt.gasUsed?.toLong() ?: 0L

        // 2) Build interactions (they'll be retargeted during each replay)
        val interactions = interactionCreationService.buildInteractions(
            abiJson = resolved.abiJson,
            contractAddress = resolved.interactionAddress,
            transactions = resolved.transactions
        )

        // 3) Replay each interaction with baseline contract deployed fresh in each fork
        val functionCalls = interactions.map { interaction ->
            val signature = SignatureUtil.signature(interaction.functionName, interaction.abiTypes)

            val outcome = forkReplayService.replayWithCustomContract(
                interaction = interaction,
                creationBytecode = compiled.creationBytecode,
                constructorArgsHex = resolved.constructorArgsHex,
                resolved = resolved
            )

            mapOutcomeToFunctionGasUsed(interaction.functionName, signature, outcome)
        }

        return GasTrackingResults(
            contractName = resolved.contractName,
            contractAddress = resolved.interactionAddress,
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