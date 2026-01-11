package de.orchestrator.gas_optimizer_orchestrator.orchestrators

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.CompilerInfo
import de.orchestrator.gas_optimizer_orchestrator.model.ExecutableInteraction
import de.orchestrator.gas_optimizer_orchestrator.model.FunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.model.GasProfile
import de.orchestrator.gas_optimizer_orchestrator.model.GasTrackingResults
import de.orchestrator.gas_optimizer_orchestrator.model.RunContext
import de.orchestrator.gas_optimizer_orchestrator.model.compilation.CompiledIrRun
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ContractResolution
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.ResolvedContractInfo
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.effectiveSourceMeta
import de.orchestrator.gas_optimizer_orchestrator.model.etherscan.toResolvedContractInfo
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.OrchestratorConstants.DEFAULT_OPTIMIZER_RUNS
import de.orchestrator.gas_optimizer_orchestrator.orchestrators.OrchestratorConstants.DEFAULT_OUT_DIR
import de.orchestrator.gas_optimizer_orchestrator.service.AnvilInteractionService
import de.orchestrator.gas_optimizer_orchestrator.service.CompilationPipeline
import de.orchestrator.gas_optimizer_orchestrator.service.ForkReplayService
import de.orchestrator.gas_optimizer_orchestrator.service.InteractionCreationService
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.GasTrackingUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.SignatureUtil
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Orchestrates gas analysis with various Solidity optimizer settings.
 *
 * This orchestrator:
 * 1. Compiles the contract with multiple optimizer run values
 * 2. Measures deployment gas for each variant
 * 3. Replays historical transactions to measure function call gas
 *
 * Results can be compared against the baseline to identify optimal settings.
 */
@Service
class SolcOptimizerOrchestrator(
    private val compilationPipeline: CompilationPipeline,
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilService: AnvilInteractionService,
    private val interactionCreationService: InteractionCreationService,
    private val forkReplayService: ForkReplayService,
    private val paths: GasOptimizerPathsProperties
) {
    private val logger = LoggerFactory.getLogger(SolcOptimizerOrchestrator::class.java)

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Runs optimizer analysis using the ContractResolution sealed class.
     */
    fun run(
        resolution: ContractResolution,
        runsList: List<Int> = DEFAULT_OPTIMIZER_RUNS,
        viaIr: Boolean = false
    ): Map<Int, GasTrackingResults> {
        logger.info(
            "Starting optimizer runs {} for: {} (viaIR={})",
            runsList,
            resolution.effectiveSourceMeta.contractName,
            viaIr
        )

        val resolved = resolution.toResolvedContractInfo()
        return executeOptimizerRuns(resolved, runsList, DEFAULT_OUT_DIR, viaIr)
    }

    // ============================================================
    // Core Orchestration
    // ============================================================

    private fun executeOptimizerRuns(
        resolved: ResolvedContractInfo,
        runsList: List<Int>,
        outDirName: String,
        viaIr: Boolean
    ): Map<Int, GasTrackingResults> {
        // Step 1: Compile all optimizer variants
        val compiledRuns = compileAllVariants(resolved, runsList, outDirName, viaIr)
        logger.info("Compiled {} optimizer variants", compiledRuns.size)

        // Step 2: Build interactions (once, reused for all variants)
        val interactions = buildInteractions(resolved)
        logger.info("Built {} interactions for replay", interactions.size)

        // Step 3: Analyze each compiled variant
        return compiledRuns.associate { compiledRun ->
            val results = analyzeCompiledVariant(compiledRun, interactions, resolved)
            logger.info("Completed analysis for runs={}", compiledRun.optimizeRuns)
            compiledRun.optimizeRuns to results
        }
    }

    // ============================================================
    // Step Implementations
    // ============================================================

    private fun compileAllVariants(
        resolved: ResolvedContractInfo,
        runsList: List<Int>,
        outDirName: String,
        viaIr: Boolean
    ): List<CompiledIrRun> {
        return compilationPipeline.compileOptimized(
            srcMeta = resolved.sourceToCompile,
            externalContractsDir = paths.externalContractsDir,
            runsList = runsList,
            outDirName = outDirName,
            viaIr = viaIr
        )
    }

    private fun buildInteractions(resolved: ResolvedContractInfo): List<ExecutableInteraction> {
        return interactionCreationService.buildInteractions(
            abiJson = resolved.abiJson,
            contractAddress = resolved.interactionAddress,
            transactions = resolved.transactions
        )
    }

    private fun analyzeCompiledVariant(
        compiledRun: CompiledIrRun,
        interactions: List<ExecutableInteraction>,
        resolved: ResolvedContractInfo
    ): GasTrackingResults {
        logger.debug("Analyzing variant with runs={}", compiledRun.optimizeRuns)

        val deploymentGas = measureDeploymentGas(compiledRun, resolved)
        logger.debug("Deployment gas for runs={}: {}", compiledRun.optimizeRuns, deploymentGas)

        val functionCalls = replayInteractions(interactions, compiledRun, resolved)

        return assembleResults(compiledRun, resolved, deploymentGas, functionCalls)
    }

    private fun measureDeploymentGas(compiledRun: CompiledIrRun, resolved: ResolvedContractInfo): Long {
        val deployBytecode = BytecodeUtil.appendConstructorArgs(
            bytecode = compiledRun.creationBytecode,
            constructorArgsHex = resolved.constructorArgsHex
        )

        val receipt = anvilService.deployOnFork(deployBytecode, resolved.creationTransaction)
        return receipt.gasUsed?.toLong() ?: 0L
    }

    private fun replayInteractions(
        interactions: List<ExecutableInteraction>,
        compiledRun: CompiledIrRun,
        resolved: ResolvedContractInfo
    ): List<FunctionGasUsed> {
        return interactions.map { interaction ->
            replaySingleInteraction(interaction, compiledRun, resolved)
        }
    }

    private fun replaySingleInteraction(
        interaction: ExecutableInteraction,
        compiledRun: CompiledIrRun,
        resolved: ResolvedContractInfo
    ): FunctionGasUsed {
        val signature = SignatureUtil.signature(interaction.functionName, interaction.abiTypes)

        logger.trace("Replaying {} (runs={}) at block {}", signature, compiledRun.optimizeRuns, interaction.blockNumber)

        val outcome = forkReplayService.replayWithCustomContract(
            interaction = interaction,
            creationBytecode = compiledRun.creationBytecode,
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
        compiledRun: CompiledIrRun,
        resolved: ResolvedContractInfo,
        deploymentGas: Long,
        functionCalls: List<FunctionGasUsed>
    ): GasTrackingResults {
        return GasTrackingResults(
            contractName = resolved.contractName,
            contractAddress = resolved.interactionAddress,
            creationBytecode = compiledRun.creationBytecode,
            runtimeBytecode = compiledRun.runtimeBytecode,
            compilerInfo = CompilerInfo(
                solcVersion = compiledRun.solcVersion ?: resolved.sourceToCompile.compilerVersion
            ),
            gasProfile = GasProfile(
                deploymentGasUsed = deploymentGas,
                functionCalls = functionCalls
            ),
            runContext = RunContext(
                chainId = OrchestratorConstants.DEFAULT_CHAIN_ID,
                rpcUrl = anvilManager.rpcUrl
            )
        )
    }
}