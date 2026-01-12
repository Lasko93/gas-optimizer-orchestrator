package de.orchestrator.gas_optimizer_orchestrator.api

import de.orchestrator.gas_optimizer_orchestrator.service.DemoDeployRunnerService
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/demo")
@Validated
class DemoController(
    private val demoDeployRunnerService: DemoDeployRunnerService
) {

    @PostMapping("/deploy-runner")
    fun runDeployRunner(
        @RequestParam
        @Pattern(
            regexp = "^0x[a-fA-F0-9]{40}$",
            message = "address must be a valid 0x-prefixed 20-byte hex address"
        )
        address: String
    ): ResponseEntity<DemoRunResult> {

        val result = demoDeployRunnerService.runDemoExclusive(address)

        return if (result.ok) {
            ResponseEntity.ok(result)
        } else {
            // busy or failed; here we use 409 for "already running"
            ResponseEntity.status(HttpStatus.CONFLICT).body(result)
        }
    }
}

data class DemoRunResult(
    val ok: Boolean,
    val message: String
)