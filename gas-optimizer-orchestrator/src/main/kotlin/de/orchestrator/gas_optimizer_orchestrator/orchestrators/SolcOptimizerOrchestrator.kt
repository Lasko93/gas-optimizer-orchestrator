package de.orchestrator.gas_optimizer_orchestrator.orchestrators


import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.docker.DockerComposeAnvilManager
import de.orchestrator.gas_optimizer_orchestrator.model.*
import de.orchestrator.gas_optimizer_orchestrator.model.compilation.CompiledIrRun
import de.orchestrator.gas_optimizer_orchestrator.service.AnvilInteractionService
import de.orchestrator.gas_optimizer_orchestrator.service.CompilationPipeline
import de.orchestrator.gas_optimizer_orchestrator.service.ForkReplayService
import de.orchestrator.gas_optimizer_orchestrator.service.InteractionCreationService
import de.orchestrator.gas_optimizer_orchestrator.utils.BytecodeUtil
import de.orchestrator.gas_optimizer_orchestrator.utils.GasTrackingUtil.mapOutcomeToFunctionGasUsed
import de.orchestrator.gas_optimizer_orchestrator.utils.SignatureUtil.signature
import org.springframework.stereotype.Service

@Service
class SolcOptimizerOrchestrator(
    private val compilationPipeline: CompilationPipeline,
    private val anvilManager: DockerComposeAnvilManager,
    private val anvilService: AnvilInteractionService,
    private val interactionCreationService: InteractionCreationService,
    private val forkReplayService: ForkReplayService,
    private val paths: GasOptimizerPathsProperties
) {

    /**
     * One-call orchestrator:
     *  - compile via-ir runs (creation+runtime)
     *  - measure deployment gas (no-fork deploy)
     *  - replay interactions on fork against srcMeta.address with runtime bytecode replaced before replay
     *
     * @return Map<optimizeRuns, GasTrackingResults>
     */
    fun runSolcOptimizerRuns(
        transactions: List<EtherscanTransaction>,
        srcMeta: ContractSourceCodeResult,
        abiJson: String,
        creationTransaction: FullTransaction,
        runsList: List<Int> = listOf(1, 200, 10_000),
        outDirName: String = "out",
        viaIrRuns: Boolean = false
    ): Map<Int, GasTrackingResults> {

        // 1) Compile all IR variants
        val compiledRuns: List<CompiledIrRun> = compilationPipeline.compileViaSolcOptimizer(
            srcMeta = srcMeta,
            externalContractsDir = paths.externalContractsDir,
            runsList = runsList,
            outDirName = outDirName,
            viaVrRuns = viaIrRuns
        )

        // 2) Build interactions once (always target the forked mainnet address)
        val interactions = interactionCreationService.buildInteractions(
            abiJson = abiJson,
            contractAddress = srcMeta.address,
            transactions = transactions
        )


        // 3) Execute each IR run
        return compiledRuns.associate { run ->

            val deployBytecode = BytecodeUtil.appendConstructorArgs(
                bytecode = run.creationBytecode,
                constructorArgsHex = srcMeta.constructorArgumentsHex
            )

            val deploymentGasUsed = anvilService.deployOnFork(deployBytecode, creationTransaction).gasUsed?.toLong() ?: 0L

            val functionCalls = interactions.map { interaction ->
                val sig = signature(interaction.functionName, interaction.abiTypes)

                val outcome = forkReplayService.replayOnForkAtPreviousBlock(
                    interaction = interaction,
                    beforeSend = {
                        anvilManager.replaceRuntimeBytecode(
                            address = srcMeta.address,
                            runtimeBytecode = run.runtimeBytecode
                        )
                    }
                )

                mapOutcomeToFunctionGasUsed(interaction.functionName, sig, outcome)
            }

            run.optimizeRuns to GasTrackingResults(
                contractName = srcMeta.contractName,
                contractAddress = srcMeta.address,
                compilerInfo = CompilerInfo(solcVersion = run.solcVersion ?: srcMeta.compilerVersion),
                creationBytecode = run.creationBytecode,
                runtimeBytecode = run.runtimeBytecode,
                gasProfile = GasProfile(
                    deploymentGasUsed = deploymentGasUsed,
                    functionCalls = functionCalls
                ),
                runContext = RunContext(
                    chainId = 1L,
                    rpcUrl = anvilManager.rpcUrl
                )
            )
        }
    }
}