package com.noop.testcentre

/**
 * The mandatory review-before-share gate. Nothing is shared until the user has seen the exact redacted
 * text and explicitly confirmed. Not skippable: confirm() is the only path to cleared. The Compose review
 * dialog binds to previewText and calls confirm() / cancel().
 */
class ReportReviewGate(
    private val entries: List<Pair<String, ByteArray>>,
    private val attachments: List<Pair<String, Long>> = emptyList(),
) {

    var isCleared: Boolean = false
        private set

    /**
     * Every text file the user is about to share, so they can read the whole bundle and cancel if anything
     * looks personal. Each entry gets a `=== <name> ===` header. [attachments] are the streamed capture
     * files, too big to show inline, so they are NAMED WITH THEIR SIZE instead: the review states
     * everything that leaves the phone, never only the part that fits on screen.
     */
    val previewText: String
        get() {
            val textBlocks = entries
                .filter { !isBinaryEntry(it.first) }
                .joinToString("\n\n") { (name, data) -> "=== $name ===\n${String(data)}" }
            val named = entries.filter { isBinaryEntry(it.first) }.map { it.first to it.second.size.toLong() } +
                attachments
            if (named.isEmpty()) return textBlocks
            val note = "=== attached (not shown above) ===\n" +
                named.joinToString("\n") { (name, bytes) -> "$name  ${humanSize(bytes)}" }
            return if (textBlocks.isEmpty()) note else textBlocks + "\n\n" + note
        }

    /** A bundle entry that is binary image bytes (not text to show inline). screenshot.png is the only one. */
    private fun isBinaryEntry(name: String): Boolean = name == DisplayScreenshot.BUNDLE_NAME

    /** Byte count as KB/MB for the attachment list, so the review states the volume, not just the name. */
    private fun humanSize(bytes: Long): String = when {
        bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** Explicit user confirmation: the only way the gate clears. */
    fun confirm() { isCleared = true }
    /** Explicit cancel: leaves the gate uncleared so the share never fires. */
    fun cancel() { isCleared = false }
}
