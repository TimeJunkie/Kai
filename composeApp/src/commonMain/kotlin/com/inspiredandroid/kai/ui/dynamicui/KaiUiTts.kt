package com.inspiredandroid.kai.ui.dynamicui

import com.inspiredandroid.kai.ui.markdown.parseMarkdown
import com.inspiredandroid.kai.ui.markdown.toSpeakableText

/**
 * TTS-friendly text for a message that may contain kai-ui fences. Routed through the unified
 * markdown parser: formatting is stripped, code blocks dropped, and kai-ui blocks walked for
 * their human-readable labels (titles, alerts, chips, table cells) so the user hears what the
 * form says, not the JSON behind it.
 */
fun String.toSpeakableText(): String = parseMarkdown(this).toSpeakableText()

/**
 * Target character count per TTS chunk. Smaller chunks start playing sooner (less
 * latency to first audio) and are less likely to hit per-request server timeouts.
 * 300 chars ≈ 2–3 sentences, fast enough for voice-cloning servers.
 */
private const val TTS_DEFAULT_CHUNK_CHARS = 300

/**
 * Splits TTS-ready [text] into chunks for sequential synthesis.
 * Split boundaries are chosen in preference order:
 *   paragraph break > sentence end (. ! ?) > clause boundary (, ; :) > word boundary
 * Each chunk is at most [maxChars] characters.  Guarantees non-empty, trimmed strings.
 *
 * Since each chunk is sent via a sequential `say()` call (which suspends until the
 * previous chunk finishes), playback is always in order — no out-of-order risk.
 */
fun splitTtsChunks(text: String, maxChars: Int = TTS_DEFAULT_CHUNK_CHARS): List<String> {
    if (text.length <= maxChars) return listOf(text).filter { it.isNotBlank() }
    val result = mutableListOf<String>()
    var remaining = text.trim()
    while (remaining.length > maxChars) {
        val window = remaining.take(maxChars)
        val split = findTtsSplitPoint(window, maxChars)
        result += remaining.take(split).trim()
        remaining = remaining.drop(split).trimStart()
    }
    if (remaining.isNotBlank()) result += remaining
    return result.filter { it.isNotBlank() }
}

/**
 * Returns the index within [window] at which to split, favouring natural language
 * boundaries in the latter two-thirds of the window. Falls back to [maxChars]
 * (hard split) if no boundary is found.
 */
private fun findTtsSplitPoint(window: String, maxChars: Int): Int {
    val minSplit = maxChars / 3

    // Paragraph break (\n\n)
    window.lastIndexOf("\n\n").takeIf { it > minSplit }?.let { return it + 2 }

    // Sentence endings followed by space or newline
    for (punct in arrayOf(". ", "! ", "? ", ".\n", "!\n", "?\n")) {
        window.lastIndexOf(punct).takeIf { it > minSplit }?.let { return it + 1 }
    }

    // Clause boundaries
    for (punct in arrayOf(", ", "; ", ": ")) {
        window.lastIndexOf(punct).takeIf { it > minSplit }?.let { return it + 1 }
    }

    // Word boundary
    window.lastIndexOf(' ').takeIf { it > minSplit }?.let { return it }

    return maxChars // hard split as last resort
}

internal fun KaiUiNode.collectSpeakableText(): String {
    val parts = mutableListOf<String>()
    walk(parts)
    return parts.asSequence().filter { it.isNotBlank() }.joinToString(". ")
}

private fun KaiUiNode.walk(parts: MutableList<String>) {
    when (this) {
        is TextNode -> parts += value

        is ButtonNode -> parts += label

        is TextInputNode -> (value ?: label ?: placeholder)?.let { parts += it }

        is CheckboxNode -> parts += label

        is SwitchNode -> parts += label

        is SliderNode -> label?.let { parts += it }

        is SelectNode -> {
            label?.let { parts += it }
            selected?.let { parts += it }
        }

        is RadioGroupNode -> {
            label?.let { parts += it }
            selected?.let { parts += it }
        }

        is ChipGroupNode -> chips.forEach { parts += it.label }

        is ProgressNode -> label?.let { parts += it }

        is CountdownNode -> label?.let { parts += it }

        is AlertNode -> {
            title?.takeIf { it.isNotBlank() }?.let { parts += it }
            parts += message
        }

        is QuoteNode -> {
            parts += text
            source?.let { parts += it }
        }

        is BadgeNode -> parts += value

        is StatNode -> {
            parts += label
            parts += value
            description?.let { parts += it }
        }

        is AvatarNode -> name?.let { parts += it }

        is ImageNode -> alt?.let { parts += it }

        is AccordionNode -> {
            parts += title
            children.forEach { it.walk(parts) }
        }

        is ColumnNode -> children.forEach { it.walk(parts) }

        is RowNode -> children.forEach { it.walk(parts) }

        is CardNode -> children.forEach { it.walk(parts) }

        is BoxNode -> children.forEach { it.walk(parts) }

        is ListNode -> items.forEach { it.walk(parts) }

        is TabsNode -> tabs.forEach { tab ->
            parts += tab.label
            tab.children.forEach { it.walk(parts) }
        }

        is TableNode -> {
            if (headers.isNotEmpty()) parts += headers.joinToString(", ")
            rows.forEach { parts += it.joinToString(", ") }
        }

        is CodeNode -> Unit

        is IconNode -> Unit

        is DividerNode -> Unit
    }
}
