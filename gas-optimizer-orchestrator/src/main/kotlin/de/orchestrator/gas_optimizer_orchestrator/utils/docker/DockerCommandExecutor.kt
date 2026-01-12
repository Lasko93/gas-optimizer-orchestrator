package de.orchestrator.gas_optimizer_orchestrator.utils.docker

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File

/**
 * Executes Docker Compose commands.
 */
@Service
class DockerCommandExecutor(
    @Value("\${docker.compose-file}")
    private val composeFilePath: String
) {
    private val logger = LoggerFactory.getLogger(DockerCommandExecutor::class.java)
    private val composeFile: File
        get() = File(composeFilePath)
    // ============================================================
    // Docker Compose Operations
    // ============================================================

    /**
     * Starts a service with docker compose up.
     */
    fun composeUp(
        service: String,
        env: Map<String, String> = emptyMap(),
        detached: Boolean = true,
        forceRecreate: Boolean = true
    ) {
        val args = mutableListOf("up")
        if (detached) args += "-d"
        if (forceRecreate) args += "--force-recreate"
        args += service

        executeCompose(args, env)
    }

    /**
     * Executes a bash script in a container.
     */
    fun composeExecBash(
        service: String,
        bashScript: String,
        env: Map<String, String> = emptyMap(),
        tty: Boolean = false
    ) {
        val args = mutableListOf("exec")
        if (!tty) args += "-T"
        args += listOf(service, "bash", "-lc", bashScript)

        executeCompose(args, env)
    }

    /**
     * Executes a bash script and returns the output.
     */
    fun composeExecBashWithOutput(
        service: String,
        bashScript: String,
        env: Map<String, String> = emptyMap(),
        tty: Boolean = false,
        failOnError: Boolean = false
    ): String {
        val args = mutableListOf("exec")
        if (!tty) args += "-T"
        args += listOf(service, "bash", "-lc", bashScript)

        return executeWithOutput(
            command = buildComposeCommand(args),
            environment = env,
            failOnError = failOnError
        )
    }

    // ============================================================
    // Command Execution
    // ============================================================

    private fun executeCompose(args: List<String>, env: Map<String, String> = emptyMap()) {
        execute(buildComposeCommand(args), env)
    }

    private fun buildComposeCommand(args: List<String>): List<String> {
        return listOf(
            "docker", "compose",
            "-f", composeFile.absolutePath,
            "-p", "gas-optimizer"
        ) + args
    }

    /**
     * Executes a command and streams output to stdout.
     *
     * @throws IllegalStateException if command fails
     */
    fun execute(command: List<String>, environment: Map<String, String> = emptyMap()) {
        logger.debug("Executing: {}", command.joinToString(" "))

        val workDir = composeFile.absoluteFile.parentFile ?: File(".")
        val processBuilder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)

        processBuilder.environment().putAll(environment)

        val process = processBuilder.start()
        val output = StringBuilder()

        process.inputStream.bufferedReader().forEachLine { line ->
            logger.trace(line)
            output.appendLine(line)
        }

        val exitCode = process.waitFor()

        if (exitCode != 0) {
            throw IllegalStateException(
                "Command failed (exit=$exitCode): $command\n--- output ---\n$output"
            )
        }
    }

    /**
     * Executes a command and returns the output.
     */
    fun executeWithOutput(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        failOnError: Boolean = false
    ): String {
        logger.debug("Executing with output: {}", command.joinToString(" "))

        val workDir = composeFile.absoluteFile.parentFile ?: File(".")
        val processBuilder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)

        processBuilder.environment().putAll(environment)

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()

        if (failOnError && exitCode != 0) {
            throw IllegalStateException(
                "Command failed (exit=$exitCode): $command\n--- output ---\n$output"
            )
        }

        return output
    }
}