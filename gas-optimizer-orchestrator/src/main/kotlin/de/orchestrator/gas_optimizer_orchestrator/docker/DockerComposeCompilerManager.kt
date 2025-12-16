package de.orchestrator.gas_optimizer_orchestrator.docker

import de.orchestrator.gas_optimizer_orchestrator.config.GasOptimizerPathsProperties
import org.springframework.stereotype.Service
import java.io.File

@Service
class DockerComposeCompilerManager(
    private val docker: DockerHelper,
    private val paths: GasOptimizerPathsProperties
) {
    private val serviceName = "compiler"

    private val hostShareDir = paths.externalContractsDir.toFile()

    fun compileViaIrRunsCombinedJson(
        solFileName: String = "",
        runsList: List<Int> = listOf(1, 200, 10_000),
        outDirName: String = "out"
    ): List<File> {

        require(hostShareDir.exists()) { "Host share dir does not exist: ${this@DockerComposeCompilerManager.hostShareDir.absolutePath}" }

        val hostSol = File(hostShareDir, solFileName)
        require(hostSol.exists()) { "Solidity file not found: ${hostSol.absolutePath}" }

        val hostOutDir = File(hostShareDir, outDirName).apply { mkdirs() }

        val runs = runsList.joinToString(" ") { it.toString() }
        val script = """
            set -euo pipefail
            mkdir -p "/share/$outDirName"
            for runs in $runs; do
              solc \
                --via-ir \
                --optimize --optimize-runs "$runs" \
                --combined-json bin,bin-runtime \
                --pretty-json \
                --base-path /share --allow-paths /share \
                "/share/$solFileName" \
                > "/share/$outDirName/viair_runs$runs.json"
            done
        """.trimIndent()

        docker.dockerComposeExecBash(serviceName, script, tty = false)

        return runsList.map { File(hostOutDir, "viair_runs$it.json") }
    }

    fun compileWithCryticTruffle(
        exportDirName: String = "crytic-export",
        cleanExportDir: Boolean = true
    ): File {
        require(hostShareDir.exists()) { "Host share dir does not exist: ${this@DockerComposeCompilerManager.hostShareDir.absolutePath}" }
        require(hostShareDir.isDirectory) { "Host share dir is not a directory: ${this@DockerComposeCompilerManager.hostShareDir.absolutePath}" }

        val hostExportDir = File(hostShareDir, exportDirName)

        val script = """
        set -euo pipefail
        cd /share

        ${if (cleanExportDir) """rm -rf "/share/$exportDirName"""" else ""}
        mkdir -p "/share/$exportDirName"

        crytic-compile . \
          --export-format truffle \
          --export-dir "/share/$exportDirName"
    """.trimIndent()

        docker.dockerComposeExecBash(serviceName, script, tty = false)

        return hostExportDir
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



}