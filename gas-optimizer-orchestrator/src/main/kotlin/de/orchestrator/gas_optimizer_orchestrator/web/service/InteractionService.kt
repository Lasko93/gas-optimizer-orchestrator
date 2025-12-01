package de.orchestrator.gas_optimizer_orchestrator.web.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import de.orchestrator.gas_optimizer_orchestrator.domain.EtherscanTransaction
import org.springframework.stereotype.Service
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.FunctionReturnDecoder
import org.web3j.abi.TypeReference
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

    fun measureInteractionsFromAbiUsingMainnetTx(
        artifactFile: File,
        contractAddress: String,
        mainnetTransactions: List<EtherscanTransaction>
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

        // 1) Map: Funktions-Signatur -> Mainnet-Txs
        val txsBySignature: Map<String, List<EtherscanTransaction>> =
            mainnetTransactions
                .filter { it.input != "0x" } // nur echte Function Calls
                .mapNotNull { tx ->
                    val normalized = normalizeEtherscanFunctionName(tx.functionName)
                    if (normalized == null) {
                        null
                    } else {
                        normalized to tx
                    }
                }
                .groupBy({ it.first }, { it.second })

        val results = mutableListOf<InteractionResult>()
        val errors = mutableListOf<InteractionError>()

        for (fnNode in txFunctions) {
            val name = fnNode.path("name").asText()
            val inputsNode = fnNode.path("inputs")
            if (!inputsNode.isArray) {
                println("WARN: Funktion $name hat kein gültiges inputs-Array – übersprungen.")
                continue
            }

            // ABI-Typen bestimmen
            val solidityTypes = inputsNode.map { it.path("type").asText() }
            val signature = abiSignature(name, solidityTypes)

            println("ABI-Funktion $signature")

            val matchingTxs = txsBySignature[signature].orEmpty()
            if (matchingTxs.isEmpty()) {
                println("Keine Mainnet-Transactions für Funktion $signature gefunden (per functionName-Match).")
                continue
            }

            val mutability = fnNode.path("stateMutability").asText()

            for (mainTx in matchingTxs) {
                try {
                    val from = mainTx.from
                    val to = contractAddress   // Achtung: forked / lokale Adresse!
                    val data = mainTx.input
                    if (data.isNullOrBlank() || data == "0x") {
                        println("Mainnet-Tx ${mainTx.hash} hat keine Input-Daten – übersprungen.")
                        continue
                    }

                    val value = if (mutability == "payable") {
                        mainTx.value.toBigIntSafe()
                    } else {
                        BigInteger.ZERO
                    }

                    val gasLimit = mainTx.gas.toBigIntSafe()
                    val gasPrice = mainTx.gasPrice.toBigIntSafe()

                    println("Replaying Tx ${mainTx.hash} as $signature from $from ...")

                    val receipt = ganacheService.sendRawTransaction(
                        from = from,
                        to = to,
                        value = value,
                        gasLimit = gasLimit,
                        gasPrice = gasPrice,
                        data = data
                    )

                    val status = receipt.status
                    if (status != null && status != "0x1") {
                        val reasonFromReceipt = receipt.revertReason
                        val msg = reasonFromReceipt
                            ?: "EVM execution failed (status=$status, gasUsed=${receipt.gasUsed})"

                        val error = InteractionError(
                            artifactFileName = artifactFile.name,
                            contractName = contractName,
                            functionName = "$signature [mainnetTx=${mainTx.hash}]",
                            functionSignature = signature,
                            txHash = receipt.transactionHash,
                            status = status,
                            gasUsed = receipt.gasUsed,
                            message = msg
                        )
                        errors += error
                        println("Interaction ERROR (revert, full-tx-replay): $error")
                        continue
                    }

                    val result = InteractionResult(
                        artifactFileName = artifactFile.name,
                        contractName = contractName,
                        functionName = "$signature [mainnetTx=${mainTx.hash}]",
                        functionSignature = signature,
                        gasUsed = receipt.gasUsed,
                        txHash = receipt.transactionHash
                    )

                    println("Interaction OK (full-tx-replay): $result")
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
                        functionName = "$signature [mainnetTx=${mainTx.hash}]",
                        functionSignature = signature,
                        txHash = txHash,
                        status = status,
                        gasUsed = gasUsed,
                        message = ex.message ?: "Unknown error"
                    )
                    errors += error
                    println("Interaction ERROR (exception, full-tx-replay): $error")
                }
            }
        }

        return InteractionReport(
            successes = results,
            errors = errors
        )
    }

    fun dummyValueForType(solidityType: String, addressIndex: Int): Type<*>? {
        return when {
            solidityType == "address" -> {
                // cycle through Ganache accounts for unique addresses
                val accounts = ganacheService.getAccounts()
                Address(accounts[ addressIndex % accounts.size ])
            }
            solidityType.startsWith("uint") -> {
                val bits = solidityType.removePrefix("uint").ifEmpty { "256" }.toInt()
                val safeVal = BigInteger.valueOf(1L)  // use 1 as a safe default
                when (bits) {                        // return matching Uint type
                    8   -> Uint8(safeVal)
                    16  -> Uint16(safeVal)
                    32  -> Uint32(safeVal)
                    64  -> Uint64(safeVal)
                    128 -> Uint128(safeVal)
                    256 -> Uint256(BigInteger.valueOf(1000L))  // e.g. a bigger dummy for 256-bit
                    else-> Uint256(BigInteger.valueOf(1000L))
                }
            }
            solidityType.startsWith("int") -> {
                val bits = solidityType.removePrefix("int").ifEmpty { "256" }.toInt()
                val safeNeg = BigInteger.valueOf(-1L)
                when (bits) {
                    8   -> Int8(safeNeg)
                    16  -> Int16(safeNeg)
                    32  -> Int32(safeNeg)
                    64  -> Int64(safeNeg)
                    128 -> Int128(safeNeg)
                    256 -> Int256(BigInteger.valueOf(-123L))
                    else-> Int256(BigInteger.valueOf(-123L))
                }
            }
            solidityType == "bool" -> Bool(true)
            solidityType == "string" -> Utf8String("dummy")
            solidityType.matches(Regex("""^bytes([1-9]|[12]\d|3[0-2])$""")) -> {
                val size = solidityType.removePrefix("bytes").toInt()
                val data = ByteArray(size) { 0x42.toByte() }  // e.g. array of 'B' bytes
                // Use reflection or manual mapping to BytesX
                when(size) {
                    32 -> Bytes32(data.copyOf(32))
                    16 -> Bytes16(data.copyOf(16))
                    8  -> Bytes8(data.copyOf(8))
                    else-> DynamicBytes(data)  // fallback to dynamic if no direct class
                }
            }
            solidityType == "bytes" -> DynamicBytes("dummy".toByteArray())
            solidityType.endsWith("[]") -> {
                val elementType = solidityType.removeSuffix("[]")
                // Recursively get one dummy element (for simplicity)
                val elementValue = dummyValueForType(elementType, addressIndex) ?: return null
                DynamicArray(elementValue.javaClass as Class<Type<*>>, listOf(elementValue))
            }
            solidityType.contains('[') && solidityType.contains(']') -> {
                // Handle fixed-size arrays like type[M]
                val elemType = solidityType.substringBefore('[')
                val lengthStr = solidityType.substringAfter('[').substringBefore(']')
                val length = lengthStr.toIntOrNull() ?: return null
                val elemValue = dummyValueForType(elemType, addressIndex) ?: return null
                // Create a list of dummy elements (could vary addressIndex for each if elem is address)
                val elems = (0 until length).map { idx ->
                    dummyValueForType(elemType, addressIndex + idx) as Type<*>
                }
                // Use appropriate StaticArray class if available, otherwise DynamicArray as fallback
                return when (length) {
                    1 -> StaticArray1(elemValue.javaClass as Class<Type<*>>, elems)
                    2 -> StaticArray2(elemValue.javaClass as Class<Type<*>>, elems)
                    3 -> StaticArray3(elemValue.javaClass as Class<Type<*>>, elems)
                    // ...
                    else -> DynamicArray(elemValue.javaClass as Class<Type<*>>, elems)
                }
            }
            else -> {
                println("WARN: Typ '$solidityType' wird noch nicht unterstützt – Funktion wird übersprungen.")
                null
            }
        }
    }

    private fun typeReferenceFor(solidityType: String): TypeReference<out Type<*>>? =
        when {
            solidityType == "address" ->
                object : TypeReference<Address>() {}
            solidityType == "bool" ->
                object : TypeReference<Bool>() {}
            solidityType == "string" ->
                object : TypeReference<Utf8String>() {}
            solidityType == "bytes" ->
                object : TypeReference<DynamicBytes>() {}

            solidityType.startsWith("uint") -> {
                val bits = solidityType.removePrefix("uint").ifEmpty { "256" }.toInt()
                when (bits) {
                    8   -> object : TypeReference<Uint8>() {}
                    16  -> object : TypeReference<Uint16>() {}
                    32  -> object : TypeReference<Uint32>() {}
                    64  -> object : TypeReference<Uint64>() {}
                    128 -> object : TypeReference<Uint128>() {}
                    256 -> object : TypeReference<Uint256>() {}
                    else-> object : TypeReference<Uint256>() {}
                }
            }

            solidityType.startsWith("int") -> {
                val bits = solidityType.removePrefix("int").ifEmpty { "256" }.toInt()
                when (bits) {
                    8   -> object : TypeReference<Int8>() {}
                    16  -> object : TypeReference<Int16>() {}
                    32  -> object : TypeReference<Int32>() {}
                    64  -> object : TypeReference<Int64>() {}
                    128 -> object : TypeReference<Int128>() {}
                    256 -> object : TypeReference<Int256>() {}
                    else-> object : TypeReference<Int256>() {}
                }
            }

            // Arrays / komplexe Typen erstmal skippen, kann man später nachrüsten
            solidityType.endsWith("[]") || solidityType.contains('[') ->
                null

            else -> {
                println("WARN: typeReferenceFor: Typ '$solidityType' wird noch nicht unterstützt.")
                null
            }
        }
    private fun abiSignature(name: String, types: List<String>): String =
        "$name(${types.joinToString(",")})"

    /**
     * Etherscan functionName z.B.:
     * "mintPublic(address nftContract, address feeRecipient, address minter, uint256 quantity)"
     * -> "mintPublic(address,address,address,uint256)"
     */
    private fun normalizeEtherscanFunctionName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val parenIdx = trimmed.indexOf('(')
        if (parenIdx <= 0 || !trimmed.endsWith(")")) {
            // fallback: nur Name
            return trimmed
        }
        val name = trimmed.substring(0, parenIdx).trim()
        val paramsPart = trimmed.substring(parenIdx + 1, trimmed.length - 1) // ohne Klammern
        if (paramsPart.isBlank()) return "$name()"

        val typesOnly = paramsPart.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { param ->
                // "address nftContract" -> "address"
                param.split(Regex("\\s+"))[0]
            }

        return "$name(${typesOnly.joinToString(",")})"
    }

    private fun String.toBigIntSafe(): BigInteger =
        this.trim().takeIf { it.isNotEmpty() }?.let { BigInteger(it) } ?: BigInteger.ZERO

}
