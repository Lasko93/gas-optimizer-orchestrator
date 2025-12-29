package de.orchestrator.gas_optimizer_orchestrator.docker

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import de.orchestrator.gas_optimizer_orchestrator.utils.CompilerHelper.normalizeSolcVersion
import org.springframework.stereotype.Service
import java.io.File

@Service
class DockerComposeCompilerManager(
    private val docker: DockerHelper,
    private val paths: GasOptimizerPathsProperties
) {
    private val serviceName = "compiler"

    private val hostShareDir = paths.externalContractsDir.toFile()


    //ToDo: Not every solc version has the same args --> Implement parser
    //ToDo: Install dependencies before running solc
    fun compileViaIrRunsCombinedJson(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList(),
        runsList: List<Int> = listOf(1, 200, 10_000),
        outDirName: String = "out"
    ): List<File> {

        useSolcVersion(solcVersion)

        require(hostShareDir.exists()) {
            "Host share dir does not exist: ${hostShareDir.absolutePath}"
        }

        val hostSol = File(hostShareDir, solFileName)
        require(hostSol.exists()) { "Solidity file not found: ${hostSol.absolutePath}" }

        val hostOutDir = File(hostShareDir, outDirName).apply { mkdirs() }
        val runsTokens = runsList.joinToString(" ") { it.toString() }
        val remapArgs = remappings.joinToString(" ")

        val script = """
        set -euo pipefail
        mkdir -p "/share/$outDirName"
        cd /share
    
        for r in $runsTokens; do
          out_file="/share/$outDirName/viair_runs${'$'}r.json"
            
          solc $remapArgs \
            "$solFileName" \
            --combined-json abi,ast,bin,bin-runtime,srcmap,srcmap-runtime,userdoc,devdoc,hashes \
            --allow-paths .,/share \
            --via-ir \
            --optimize \
            --optimize-runs "${'$'}r" \
            > "${'$'}out_file"
          
          test -f "${'$'}out_file"
        done
    """.trimIndent()

        docker.dockerComposeExecBash(serviceName, script, tty = false)

        return runsList.map { File(hostOutDir, "viair_runs$it.json") }
    }

    fun compileSolcNoOptimizeCombinedJson(
        solFileName: String,
        solcVersion: String,
        remappings: List<String> = emptyList(),
        outFileName: String = "baseline_noopt.json",
        outDirName: String = "out"
    ): File {

        useSolcVersion(solcVersion)

        require(hostShareDir.exists()) { "Host share dir does not exist: ${hostShareDir.absolutePath}" }
        val hostSol = File(hostShareDir, solFileName)
        require(hostSol.exists()) { "Solidity file not found: ${hostSol.absolutePath}" }

        val hostOutDir = File(hostShareDir, outDirName).apply { mkdirs() }
        val hostOutFile = File(hostOutDir, outFileName)

        val remapArgs = remappings.joinToString(" ")

        val script = """
    set -euo pipefail
    mkdir -p "/share/$outDirName"
    cd /share

    # Compile directly with solc
    solc $remapArgs \
      "$solFileName" \
      --combined-json abi,ast,bin,bin-runtime,srcmap,srcmap-runtime,userdoc,devdoc,hashes \
      --allow-paths .,/share \
      > "/share/$outDirName/$outFileName"
""".trimIndent()

        docker.dockerComposeExecBash(serviceName, script, tty = false)

        return hostOutFile
    }

    /**
     * Deletes ALL contents of externalContracts (files + directories),
     * but keeps the externalContracts folder itself.
     */
    fun cleanExternalContractsDir() {
        require(hostShareDir.exists()) { "Host share dir does not exist: ${this@DockerComposeCompilerManager.hostShareDir.absolutePath}" }
        require(hostShareDir.isDirectory) { "Host share dir is not a directory: ${this@DockerComposeCompilerManager.hostShareDir.absolutePath}" }

        val children = hostShareDir.listFiles().orEmpty()
        children.forEach { it.deleteRecursively() }

        // sanity check
        val leftover = hostShareDir.listFiles().orEmpty()
        check(leftover.isEmpty()) {
            "Failed to clean externalContracts dir. Leftover: ${leftover.joinToString { it.name }}"
        }
    }

    fun useSolcVersion(solcVersionRaw: String) {
        val v = normalizeSolcVersion(solcVersionRaw)
        val script = """solc-select use "$v" --always-install && chmod +x /home/ethsec/.solc-select/artifacts/solc-$v/solc-$v 2>/dev/null; solc --version"""
        docker.dockerComposeExecBash(serviceName, script, tty = false)
    }
}