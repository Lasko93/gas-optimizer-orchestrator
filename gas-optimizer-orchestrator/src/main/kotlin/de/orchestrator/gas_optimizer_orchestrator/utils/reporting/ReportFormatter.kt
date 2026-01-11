package de.orchestrator.gas_optimizer_orchestrator.utils.reporting

/**
 * Formatting utilities for console reports.
 */
object ReportFormatter {

    const val DEFAULT_LINE_WIDTH = 66

    // ============================================================
    // Box Drawing
    // ============================================================

    /**
     * Creates a header box with double-line borders.
     */
    fun headerBox(title: String, width: Int = DEFAULT_LINE_WIDTH): String {
        return buildString {
            appendLine("╔${"═".repeat(width)}╗")
            appendLine("║${centerText(title, width)}║")
            appendLine("╚${"═".repeat(width)}╝")
        }
    }

    /**
     * Creates a header box with multiple lines.
     */
    fun headerBox(lines: List<String>, width: Int = DEFAULT_LINE_WIDTH): String {
        return buildString {
            appendLine("╔${"═".repeat(width)}╗")
            lines.forEach { line ->
                appendLine("║${centerText(line, width)}║")
            }
            appendLine("╚${"═".repeat(width)}╝")
        }
    }

    /**
     * Creates a section header with single-line borders.
     */
    fun sectionHeader(title: String, width: Int = DEFAULT_LINE_WIDTH): String {
        return buildString {
            appendLine("┌${"─".repeat(width)}┐")
            appendLine("│${centerText(title, width)}│")
            appendLine("└${"─".repeat(width)}┘")
        }
    }

    /**
     * Creates a horizontal separator line.
     */
    fun separator(width: Int = DEFAULT_LINE_WIDTH, char: Char = '─'): String {
        return char.toString().repeat(width)
    }

    // ============================================================
    // Text Formatting
    // ============================================================

    /**
     * Centers text within a given width.
     */
    fun centerText(text: String, width: Int): String {
        val padding = (width - text.length) / 2
        val leftPad = " ".repeat(padding.coerceAtLeast(0))
        val rightPad = " ".repeat((width - text.length - padding).coerceAtLeast(0))
        return "$leftPad$text$rightPad"
    }

    /**
     * Truncates text with ellipsis if too long.
     */
    fun truncate(text: String, maxLength: Int = 50): String {
        return if (text.length > maxLength) {
            text.take(maxLength - 3) + "..."
        } else {
            text
        }
    }

    /**
     * Formats a label-value pair with consistent alignment.
     */
    fun labelValue(label: String, value: Any?, labelWidth: Int = 18): String {
        return "  ${label.padEnd(labelWidth)} $value"
    }

    // ============================================================
    // Status Indicators
    // ============================================================

    /**
     * Returns a success/failure indicator.
     */
    fun statusIcon(success: Boolean): String {
        return if (success) "✓" else "✗"
    }

    /**
     * Returns a change direction indicator.
     */
    fun changeIcon(delta: Long): String = when {
        delta < 0 -> "📉"
        delta > 0 -> "📈"
        else -> "➖"
    }


    // ============================================================
    // Number Formatting
    // ============================================================

    /**
     * Formats a delta value with sign prefix.
     */
    fun formatDelta(value: Long): String {
        return if (value >= 0) "+$value" else value.toString()
    }
}