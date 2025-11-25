package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.web3j.abi.datatypes.*
import org.web3j.abi.datatypes.generated.*
import java.io.File
import java.math.BigInteger
import org.web3j.protocol.exceptions.TransactionException
import org.web3j.abi.datatypes.Function as Web3Function

@Service
class InteractionService(
    private val objectMapper: ObjectMapper,
    private val ganacheService: GanacheService
) {

    private val account: String = ganacheService.getAccounts().first()
    private val DEFAULT_PAYABLE_VALUE: BigInteger =
        BigInteger.valueOf(1_000_000_000_000_000L)

    data class InteractionResult(
        val artifactFileName: String,
        val contractName: String,
        val functionName: String,
        val functionSignature: String,
        val gasUsed: BigInteger,
        val txHash: String
    )

    data class InteractionError(
        val artifactFileName: String,
        val contractName: String,
        val functionName: String,
        val functionSignature: String,
        val txHash: String?,
        val status: String?,
        val gasUsed: BigInteger?,
        val message: String
    )

    data class InteractionReport(
        val successes: List<InteractionResult>,
        val errors: List<InteractionError>
    )

    fun measureInteractionsFromAbi(
        artifactFile: File,
        contractAddress: String
    ): InteractionReport {
        val root: JsonNode = objectMapper.readTree(artifactFile)
        val contractName = root.path("contractName").asText(artifactFile.nameWithoutExtension)
        val abi = root.path("abi")
        require(abi.isArray) { "Artifact enthält kein 'abi'-Array." }

        val txFunctions = abi.filter { fn ->
            fn.path("type").asText() == "function" &&
                    fn.path("stateMutability").asText() !in listOf("view", "pure")
        }

        require(account.isNotEmpty()) { "Keine Accounts auf Ganache gefunden." }

        val results = mutableListOf<InteractionResult>()
        val errors = mutableListOf<InteractionError>()

        for (fnNode in txFunctions) {
            val name = fnNode.path("name").asText()
            val inputsNode = fnNode.path("inputs")

            if (!inputsNode.isArray) {
                println("WARN: Funktion $name hat kein gültiges inputs-Array – übersprungen.")
                continue
            }

            val inputTypes = mutableListOf<Type<*>>()
            var addressCounter = 0
            var unsupported = false

            val sigParamTypes = mutableListOf<String>()

            for (param in inputsNode) {
                val typeStr = param.path("type").asText()
                sigParamTypes += typeStr

                val value = dummyValueForType(typeStr, addressCounter)
                if (value == null) {
                    unsupported = true
                    break
                }
                if (typeStr == "address" || (typeStr.endsWith("[]") && typeStr.startsWith("address"))) {
                    addressCounter++
                }
                inputTypes += value
            }

            if (unsupported) {
                println("Funktion $name wird wegen nicht unterstützter Typen übersprungen.")
                continue
            }

            val mutability = fnNode.path("stateMutability").asText()
            val value = if (mutability == "payable") {
                BigInteger.valueOf(1_000_000_000_000_000L) // z.B. 0.001 ETH
            } else {
                BigInteger.ZERO
            }

            val function = Web3Function(name, inputTypes, emptyList())
            val signature = "$name(${sigParamTypes.joinToString(",")})"
            val label = "$name(${inputTypes.joinToString(",") { it.typeAsString }})"

            try {
                val receipt = ganacheService.sendFunctionTx(
                    contractAddress = contractAddress,
                    function = function,
                    value = value
                )

                val status = receipt.status

                // Wenn die EVM mit Revert/Fehler beendet hat
                if (status != null && status != "0x1") {
                    // Nutze revertReason direkt aus dem Receipt, falls vorhanden
                    val reasonFromReceipt = receipt.revertReason

                    val msg = reasonFromReceipt
                        ?: "EVM execution failed (status=$status, gasUsed=${receipt.gasUsed})"

                    val error = InteractionError(
                        artifactFileName = artifactFile.name,
                        contractName = contractName,
                        functionName = label,
                        functionSignature = signature,
                        txHash = receipt.transactionHash,
                        status = status,
                        gasUsed = receipt.gasUsed,
                        message = msg
                    )
                    errors += error
                    println("Interaction ERROR (revert): $error")
                    continue
                }

                // Erfolgreiche Transaktion
                val result = InteractionResult(
                    artifactFileName = artifactFile.name,
                    contractName = contractName,
                    functionName = label,
                    functionSignature = signature,
                    gasUsed = receipt.gasUsed,
                    txHash = receipt.transactionHash
                )

                println("Interaction OK: $result")
                results += result
            } catch (ex: Exception) {
                val (txHash, status, gasUsed) = if (ex is TransactionException) {
                    val receiptOpt = ex.transactionReceipt
                    val receipt = receiptOpt.orElse(null)
                    Triple(
                        receipt?.transactionHash,
                        receipt?.status,
                        receipt?.gasUsed
                    )
                } else {
                    Triple(null, null, null)
                }
                val error = InteractionError(
                    artifactFileName = artifactFile.name,
                    contractName = contractName,
                    functionName = label,
                    functionSignature = signature,
                    txHash = txHash,
                    status = status,
                    gasUsed = gasUsed,
                    message = ex.message ?: "Unknown error"
                )
                errors += error
                println("Interaction ERROR (exception): $error")
            }
        }

        return InteractionReport(
            successes = results,
            errors = errors
        )
    }

    fun dummyValueForType(
        solidityType: String,
        addressIndex: Int
    ): Type<*>? {
        return when {
            solidityType == "address" ->
                Address(account)

            solidityType.startsWith("uint") ->
                Uint256(BigInteger.valueOf(1_000L))

            solidityType.startsWith("int") ->
                Int256(BigInteger.valueOf(-123L))

            solidityType == "bool" ->
                Bool(true)

            solidityType == "string" ->
                Utf8String("dummy")

            solidityType == "bytes32" ->
                Bytes32("dummy-value".toByteArray().copyOf(32))

            solidityType == "bytes" ->
                DynamicBytes("dummy".toByteArray())

            // einfache 1D-Arrays wie address[], uint256[] etc.
            solidityType.endsWith("[]") -> {
                val elementType = solidityType.removeSuffix("[]")
                val element = dummyValueForType(elementType, addressIndex)
                    ?: return null

                @Suppress("UNCHECKED_CAST")
                DynamicArray(
                    element.javaClass as Class<Type<*>>,
                    listOf(element)
                )
            }

            // struct/tuple, mapping, komplexere Typen → erstmal skippen
            else -> {
                println("WARN: Typ '$solidityType' wird noch nicht unterstützt – Funktion wird übersprungen.")
                null
            }
        }
    }
}
