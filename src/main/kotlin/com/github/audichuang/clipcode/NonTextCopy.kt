package com.github.audichuang.clipcode

import com.intellij.openapi.project.Project
import java.util.Locale

/** What the user picked in the non-text dialog. Esc and closing the dialog are [CANCEL]. */
enum class NonTextChoice { INCLUDE, EXCLUDE, CANCEL }

/** Asks whether to carry non-text files. Injectable so the copy flow runs headless in tests. */
fun interface NonTextChooser {
    fun choose(project: Project, summary: NonTextCopySummary): NonTextChoice
}

/**
 * Everything the dialog shows, derived from the Include result itself (spec non-text-files.md,
 * "Asking the user"): the same ordered entries and the same file-count limit the Include copy used.
 */
data class NonTextCopySummary(
    /** Carried non-text files in payload order: clipboard path and raw size. */
    val carried: List<Item>,
    /** UTF-8 byte length of the Include payload. */
    val estimatedPayloadBytes: Long,
    /** Non-text files read and found over the non-text limit (copied as size placeholders). */
    val nonTextOverLimit: Int,
    /** Files over both per-file limits: not read, copied as size placeholders. */
    val unreadOverLimit: Int,
    /** Files that passed the filters but were left out by the file-count limit. */
    val limitLeftOut: Int,
    val nonTextLimitBytes: Long,
    val headerFormat: String
) {
    data class Item(val path: String, val size: Long)

    val totalBytes: Long get() = carried.sumOf { it.size }
}

/** Pure logic of the non-text copy: per-file classification and UI text. No IDE dependency. */
object NonTextCopy {
    const val DIALOG_PATH_LIMIT = 10
    const val NOTICE_PATH_LIMIT = 5

    sealed class Outcome {
        /** Today's `// File skipped: size exceeds limit (<n> bytes)` entry. */
        data class SizePlaceholder(val size: Long, val unread: Boolean, val nonTextOverLimit: Boolean) : Outcome()
        class Text(val content: String) : Outcome()
        class Carried(val bytes: ByteArray) : Outcome()
        object Unreadable : Outcome()
    }

    /**
     * Include-mode decision for one file. A file over both limits is never read. A read file is held to
     * the limit of its kind. A file of a binary file type whose bytes decode as strict UTF-8 keeps
     * today's behaviour: size placeholder over the text limit, otherwise skipped.
     * [read] returns null when the file cannot be read.
     */
    fun classify(
        length: Long,
        isBinaryType: Boolean,
        textLimitBytes: Long,
        nonTextLimitBytes: Long,
        read: () -> ByteArray?
    ): Outcome {
        if (length > maxOf(textLimitBytes, nonTextLimitBytes)) {
            return Outcome.SizePlaceholder(length, unread = true, nonTextOverLimit = false)
        }
        val bytes = read() ?: return Outcome.Unreadable
        val size = bytes.size.toLong()
        val text = Utf8Text.decodeOrNull(bytes)
        if (text != null) {
            if (size > textLimitBytes) return Outcome.SizePlaceholder(size, unread = false, nonTextOverLimit = false)
            if (isBinaryType) return Outcome.Unreadable
            return Outcome.Text(text)
        }
        if (size > nonTextLimitBytes) return Outcome.SizePlaceholder(size, unread = false, nonTextOverLimit = true)
        return Outcome.Carried(bytes)
    }

    fun sizePlaceholderLine(size: Long): String = "// File skipped: size exceeds limit ($size bytes)"

    /** Byte length of [text] encoded as UTF-8 (a lone surrogate counts as the one-byte `?` it encodes to). */
    fun utf8Length(text: String): Long {
        var n = 0L
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.code < 0x80 -> n += 1
                c.code < 0x800 -> n += 2
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                    n += 4
                    i++
                }
                Character.isSurrogate(c) -> n += 1
                else -> n += 3
            }
            i++
        }
        return n
    }

    fun formatSize(bytes: Long): String = when {
        bytes == 1L -> "1 byte"
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }

    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

    fun limitLeftOutSentence(n: Int): String = "The file-count limit left out ${files(n)}"

    data class DialogText(
        val found: String,
        val estimate: String,
        /** Shortened path and its size, first [DIALOG_PATH_LIMIT] carried files. */
        val paths: List<Pair<String, String>>,
        val more: String?,
        val notes: List<String>,
        /** Non-null when Include must be disabled; the reason shown in the dialog. */
        val includeUnavailable: String?
    )

    fun dialogText(summary: NonTextCopySummary): DialogText {
        val n = summary.carried.size
        val notes = buildList {
            if (summary.nonTextOverLimit > 0) {
                add("Non-text files over the ${formatSize(summary.nonTextLimitBytes)} limit, not copied: ${summary.nonTextOverLimit}")
            }
            if (summary.unreadOverLimit > 0) {
                add("Files over both size limits, not read, copied as size placeholders: ${summary.unreadOverLimit}")
            }
            if (summary.limitLeftOut > 0) add(limitLeftOutSentence(summary.limitLeftOut))
        }
        return DialogText(
            found = "Non-text files found: $n (total ${formatSize(summary.totalBytes)}).",
            estimate = "Estimated payload with them: ${formatSize(summary.estimatedPayloadBytes)}",
            paths = summary.carried.take(DIALOG_PATH_LIMIT).map { shortenMiddle(it.path) to formatSize(it.size) },
            more = if (n > DIALOG_PATH_LIMIT) "and ${n - DIALOG_PATH_LIMIT} more" else null,
            notes = notes,
            includeUnavailable = if (ClipboardRestoreParser.headerFormatUnescapable(summary.headerFormat)) {
                "Include is unavailable: the header format \"${summary.headerFormat}\" would make Base64 lines read as file headers."
            } else {
                null
            }
        )
    }

    /** Extra completion-notice lines after Include, one category per line (plain text, not HTML-escaped). */
    fun includeNoticeLines(nonTextCopied: Int, unreadableCount: Int, unreadablePaths: List<String>): List<String> =
        listOf("Non-text files copied as Base64: $nonTextCopied.") + unreadableLines(unreadableCount, unreadablePaths)

    /** "and N more" is [unreadableCount] minus the paths shown, so the line always adds up to the count. */
    fun unreadableLines(unreadableCount: Int, unreadablePaths: List<String>): List<String> = buildList {
        if (unreadableCount <= 0) return@buildList
        add("Unreadable: $unreadableCount")
        val shown = unreadablePaths.take(minOf(NOTICE_PATH_LIMIT, unreadableCount))
        shown.forEach { add(shortenMiddle(it)) }
        if (unreadableCount > shown.size) add("and ${unreadableCount - shown.size} more")
    }

    /** Shortest name budget worth keeping a folder prefix for. */
    private const val MIN_NAME_BUDGET = 16

    /**
     * Shortens [path] to at most [maxChars] code points so the file name stays visible: the folder
     * part shrinks first, down to its first segment and an ellipsis; only then is the file name
     * shortened in the middle, keeping its extension. Cuts fall on whole characters (never inside a
     * surrogate pair or before a combining mark) and the ellipsis sits directly against what follows.
     */
    fun shortenMiddle(path: String, maxChars: Int = 70): String {
        if (cpLen(path) <= maxChars) return path
        val slash = path.lastIndexOf('/')
        if (slash < 0) return shortenName(path, maxChars)
        val folder = path.substring(0, slash)
        val name = path.substring(slash + 1)
        val firstSegment = folder.substringBefore('/')
        val folderBudget = maxChars - 2 - cpLen(name)
        if (folderBudget >= cpLen(firstSegment)) return cpTake(folder, folderBudget) + "…/" + name
        val prefix = "$firstSegment…/"
        val nameBudget = maxChars - cpLen(prefix)
        if (nameBudget >= MIN_NAME_BUDGET) return prefix + shortenName(name, nameBudget)
        return "…/" + shortenName(name, maxChars - 2)
    }

    private fun shortenName(name: String, budget: Int): String {
        if (cpLen(name) <= budget) return name
        if (budget <= 1) return "…"
        val dot = name.lastIndexOf('.')
        var ext = if (dot > 0) name.substring(dot) else ""
        if (cpLen(ext) > budget - 3) ext = ""
        val stem = name.substring(0, name.length - ext.length)
        val stemBudget = budget - 1 - cpLen(ext)
        val head = (stemBudget + 1) / 2
        val tail = stemBudget - head
        return cpTake(stem, head) + "…" + cpTakeLast(stem, tail) + ext
    }

    private fun cpLen(s: String) = s.codePointCount(0, s.length)

    /** True for a code point that belongs to the character before it. */
    private fun isExtending(cp: Int): Boolean {
        val type = Character.getType(cp)
        return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() || cp == 0x200D || cp in 0xFE00..0xFE0F
    }

    private fun cpTake(s: String, n: Int): String {
        val cps = s.codePoints().toArray()
        var k = n.coerceIn(0, cps.size)
        while (k in 1 until cps.size && isExtending(cps[k])) k--
        return String(cps, 0, k)
    }

    private fun cpTakeLast(s: String, n: Int): String {
        val cps = s.codePoints().toArray()
        var start = (cps.size - n.coerceIn(0, cps.size))
        while (start < cps.size && isExtending(cps[start])) start++
        return String(cps, start, cps.size - start)
    }
}
