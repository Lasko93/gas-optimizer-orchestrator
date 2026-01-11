package de.orchestrator.gas_optimizer_orchestrator.orchestrators

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.model.CompilerInfo
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.model.GasProfile
import de.orchestrator.gas_optimizer_orchestrator.model.GasTrackingResults
import de.orchestrator.gas_optimizer_orchestrator.model.RunContext
import de.orchestrator.gas_optimizer_orchestrator.model.compilation.CompiledContract
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractResolution
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ResolvedContractInfo
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.effectiveSourceMeta
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.toResolvedContractInfo
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.OrchestratorConstants.DEFAULT_CHAIN_ID
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.OrchestratorConstants.DEFAULT_RPC_URL
import de.orchestrator.gas_optimizer_orchestrator.service.anvil.AnvilInteractionService
import de.orchestrator.gas_optimizer_orchestrator.service.compilation.CompilationPipeline
import de.orchestrator.gas_optimizer_orchestrator.service.anvil.ForkReplayService
import de.orchestrator.gas_optimizer_orchestrator.service.InteractionCreationService
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.GasTrackingUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.SignatureUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Orchestrates the initial (baseline) gas analysis run.
 *
 * This orchestrator:
 * 1. Compiles the contract without optimization
 * 2. Measures deployment gas
 * 3. Replays historical transactions to measure function call gas
 *
 * The results serve as a baseline for comparing optimized versions.
 */
@Service
class InitialRunOrchestrator(
    private val compilationPipeline: CompilationPipeline,
    private val anvilService: AnvilInteractionService,
    private val interactionCreationService: InteractionCreationService,
    private val forkReplayService: ForkReplayService,
    private val paths: GasOptimizerPathsProperties
) {
    private val logger = LoggerFactory.getLogger(InitialRunOrchestrator::class.java)

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Runs the initial gas analysis using the new ContractResolution sealed class.
     */
    fun run(resolution: ContractResolution): GasTrackingResults {
        logger.info("Starting initial run for: {}", resolution.effectiveSourceMeta.contractName)

        val resolved = resolution.toResolvedContractInfo()
        return executeInitialRun(resolved)
    }


    // ============================================================
    // Core Orchestration
    // ============================================================

    private fun executeInitialRun(resolved: ResolvedContractInfo): GasTrackingResults {

        // Step 1: Compile contract without optimization
        val compiled = compileBaseline(resolved)
        logger.info("Baseline compilation complete")

        // Step 2: Measure deployment gas
        val deploymentGas = measureDeploymentGas(compiled, resolved)
        logger.info("Deployment gas measured: {}", deploymentGas)

        // Step 3: Build interactions from historical transactions
        val interactions = buildInteractions(resolved)
        logger.info("Built {} interactions for replay", interactions.size)

        // Step 4: Replay interactions and measure gas
        val functionCalls = replayInteractions(interactions, compiled, resolved)
        logger.info("Replayed {} function calls", functionCalls.size)

        // Step 5: Assemble results
        return assembleResults(resolved, compiled, deploymentGas, functionCalls)
    }

    // ============================================================
    // Step Implementations
    // ============================================================

    private fun compileBaseline(resolved: ResolvedContractInfo): CompiledContract {
        val srcMeta = resolved.sourceToCompile

        return compilationPipeline.compileBaseline(
            srcMeta = srcMeta,
            externalContractsDir = paths.externalContractsDir,
            outFileName = "${srcMeta.contractName}.json"
        )
    }

    private fun measureDeploymentGas(
        compiled: CompiledContract,
        resolved: ResolvedContractInfo
    ): Long {
        val srcMeta = resolved.sourceToCompile

        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = compiled.creationBytecode,
            constructorArgsHex = srcMeta.constructorArgumentsHex
        )

        val receipt = anvilService.deployOnFork(deployBytecode, resolved.creationTransaction)

        return receipt.gasUsed?.toLong() ?: 0L
    }

    private fun buildInteractions(resolved: ResolvedContractInfo): List<ExecutableInteraction> {
        return interactionCreationService.buildInteractions(
            abiJson = resolved.abiJson,
            contractAddress = resolved.interactionAddress,
            transactions = resolved.transactions
        )
    }

    private fun replayInteractions(
        interactions: List<ExecutableInteraction>,
        compiled: CompiledContract,
        resolved: ResolvedContractInfo
    ): List<FunctionGasUsed> {
        return interactions.map { interaction ->
            replaySingleInteraction(interaction, compiled, resolved)
        }
    }

    private fun replaySingleInteraction(
        interaction: ExecutableInteraction,
        compiled: CompiledContract,
        resolved: ResolvedContractInfo
    ): FunctionGasUsed {
        val signature = SignatureUtil.signature(interaction.functionName, interaction.abiTypes)

        logger.debug("Replaying: {} at block {}", signature, interaction.blockNumber)

        val outcome = forkReplayService.replayWithCustomContract(
            interaction = interaction,
            creationBytecode = compiled.creationBytecode,
            constructorArgsHex = resolved.constructorArgsHex,
            resolved = resolved
        )

        return GasTrackingUtil.mapOutcomeToFunctionGasUsed(
            functionName = interaction.functionName,
            functionSignature = signature,
            outcome = outcome
        )
    }

    private fun assembleResults(
        resolved: ResolvedContractInfo,
        compiled: CompiledContract,
        deploymentGas: Long,
        functionCalls: List<FunctionGasUsed>
    ): GasTrackingResults {
        val srcMeta = resolved.sourceToCompile

        return GasTrackingResults(
            contractName = resolved.contractName,
            contractAddress = resolved.interactionAddress,
            creationBytecode = compiled.creationBytecode,
            runtimeBytecode = compiled.runtimeBytecode,
            compilerInfo = CompilerInfo(solcVersion = srcMeta.compilerVersion),
            gasProfile = GasProfile(
                deploymentGasUsed = deploymentGas,
                functionCalls = functionCalls
            ),
            runContext = RunContext(
                chainId = DEFAULT_CHAIN_ID,
                rpcUrl = DEFAULT_RPC_URL
            )
        )
    }
}