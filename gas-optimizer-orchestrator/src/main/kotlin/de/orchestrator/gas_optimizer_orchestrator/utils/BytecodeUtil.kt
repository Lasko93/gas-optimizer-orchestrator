package de.orchestrator.gas_optimizer_orchestrator.utils

object BytecodeUtil {

    fun appendConstructorArgs(bytecode: String, constructorArgsHex: String): String {
        val args = constructorArgsHex.trim()
        if (args.isBlank() || args == "0x") return normalize0x(bytecode)

        val bcNo0x = normalize0x(bytecode).removePrefix("0x")
        val argsNo0x = normalize0x(args).removePrefix("0x")
        return "0x$bcNo0x$argsNo0x"
    }

    fun normalize0x(hex: String): String {
        val h = hex.trim()
        return if (h.startsWith("0x")) h else "0x$h"
    }
}
