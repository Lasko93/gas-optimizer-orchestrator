package de.orchestrator.gas_optimizer_orchestrator.docker

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.utils.CompilerHelper
import org.springframework.stereotype.Service
import java.io.File

@Service
class DockerComposeCompilerManager(
    private val docker: DockerHelper,
    private val paths: GasOptimizerPathsProperties
) {
    private val serviceName = "compiler"
    private val hostShareDir = paths.externalContractsDir.toFile()

    fun compileViaSolcOptimizer(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList(),
        runsList: List<Int> = listOf(1, 200, 10_000),
        outDirName: String = "out",
        viaIrRuns: Boolean = true
    ): List<File> {
        useSolcVersion(solcVersion)

        requireValidSetup(solFileName)
        val hostOutDir = File(hostShareDir, outDirName).apply { mkdirs() }

        val viaIrConfig = CompilerHelper.resolveViaIrConfig(viaIrRuns, solcVersion)
        val script = CompilerHelper.optimizedCompilationScript(
            solFileName = solFileName,
            remappings = remappings,
            runsList = runsList,
            outDirName = outDirName,
            viaIrConfig = viaIrConfig
        )

        docker.dockerComposeExecBash(serviceName, script, tty = false)

        return runsList.map { File(hostOutDir, "${viaIrConfig.filePrefix}$it.json") }
    }

    fun compileSolcNoOptimizeCombinedJson(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList(),
        outFileName: String = "baseline_noopt.json",
        outDirName: String = "out"
    ): File {
        useSolcVersion(solcVersion)

        requireValidSetup(solFileName)
        val hostOutDir = File(hostShareDir, outDirName).apply { mkdirs() }

        val script = CompilerHelper.baselineCompilationScript(
            solFileName = solFileName,
            remappings = remappings,
            outDirName = outDirName,
            outFileName = outFileName
        )

        docker.dockerComposeExecBash(serviceName, script, tty = false)

        return File(hostOutDir, outFileName)
    }

    fun cleanExternalContractsDir() {
        require(hostShareDir.exists()) { "Host share dir does not exist: ${hostShareDir.absolutePath}" }
        require(hostShareDir.isDirectory) { "Host share dir is not a directory: ${hostShareDir.absolutePath}" }

        hostShareDir.listFiles().orEmpty().forEach { it.deleteRecursively() }

        val leftover = hostShareDir.listFiles().orEmpty()
        check(leftover.isEmpty()) {
            "Failed to clean externalContracts dir. Leftover: ${leftover.joinToString { it.name }}"
        }
    }

    fun useSolcVersion(solcVersionRaw: String) {
        val script = CompilerHelper.solcSelectScript(solcVersionRaw)
        docker.dockerComposeExecBash(serviceName, script, tty = false)
    }

    private fun requireValidSetup(solFileName: String) {
        require(hostShareDir.exists()) { "Host share dir does not exist: ${hostShareDir.absolutePath}" }
        val hostSol = File(hostShareDir, solFileName)
        require(hostSol.exists()) { "Solidity file not found: ${hostSol.absolutePath}" }
    }
}