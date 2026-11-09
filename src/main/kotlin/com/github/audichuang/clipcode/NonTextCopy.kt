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
    val headerFormat: String,
    /**
     * True when more files were left out than [limitLeftOut]: counting stopped at
     * [NonTextCopy.LEFT_OUT_COUNT_CAP], or the walk stopped before listing a folder past the limit.
     */
    val limitLeftOutCapped: Boolean = false
) {
    data class Item(val path: String, val size: Long)

    val totalBytes: Long get() = carried.sumOf { it.size }

    /** The Include estimate the 50 MiB ceiling is judged on: never lower than the raw total of the carried files. */
    val effectiveEstimateBytes: Long get() = maxOf(estimatedPayloadBytes, totalBytes)
}

/** Why a reached item was skipped and named in the completion notice (spec non-text-files.md, decision D2). */
enum class SkipCategory(val label: String) {
    UNREADABLE("Unreadable"),
    SYMLINKED_FOLDER("Symlinked folder"),
    NAME_NOT_PORTABLE("Name not portable")
}

/**
 * Count and the first few real names of every skip category. The only way to record a skip: count and
 * names move together, so a notice never says "Unreadable: 2" while listing one path. Only the names a
 * notice can show are kept (mirror of OpenIDEA `note_skipped`). A name is counted once per category: a
 * folder selected together with its parent is reached twice, and the notice must not say 2 for one item.
 */
class SkipNotes {
    private val counts = IntArray(SkipCategory.entries.size)
    private val names = SkipCategory.entries.map { mutableListOf<String>() }
    private val seen = SkipCategory.entries.map { mutableSetOf<String>() }

    fun note(category: SkipCategory, name: String) {
        if (!seen[category.ordinal].add(name)) return
        counts[category.ordinal]++
        val shown = names[category.ordinal]
        if (shown.size < NonTextCopy.NOTICE_PATH_LIMIT) shown.add(name)
    }

    fun count(category: SkipCategory): Int = counts[category.ordinal]

    fun names(category: SkipCategory): List<String> = names[category.ordinal].toList()

    /** True when something was skipped as a symlinked folder or an unportable name: Exclude is then not today's copy. */
    val hasNamedSkips: Boolean
        get() = count(SkipCategory.SYMLINKED_FOLDER) > 0 || count(SkipCategory.NAME_NOT_PORTABLE) > 0

    // Value equality, so payloads holding notes still compare as data (Exclude == default build).
    override fun equals(other: Any?): Boolean =
        other is SkipNotes && counts.contentEquals(other.counts) && names == other.names

    override fun hashCode(): Int = 31 * counts.contentHashCode() + names.hashCode()
}

/** Pure logic of the non-text copy: per-file classification and UI text. No IDE dependency. */
object NonTextCopy {
    /**
     * Left-out files are counted up to this many past the limit; beyond it the number reads "1000+". Only
     * entries already listed are counted: the walk never goes on past the limit just to count.
     */
    const val LEFT_OUT_COUNT_CAP = 1000
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
     * the limit of its kind. The bytes alone decide (X16): an IDE file type is never consulted, so a
     * binary-typed file whose bytes are strict UTF-8 is text. [read] returns null when the file cannot be read.
     */
    fun classify(
        length: Long,
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

    /**
     * The one size format of every user-visible place (dialog, notices, paste preview), mirror of OpenIDEA
     * `formatBytes`: under 1024 "N B"; otherwise KB / MB / GB with one decimal below 10, none from 10 up.
     */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB")
        var value = bytes / 1024.0
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return String.format(Locale.ROOT, if (value >= 10) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    /** The whole-payload ceiling of an Include copy (OpenIDEA `CLIPBOARD_PAYLOAD_MAX`): at the limit is allowed. */
    const val INCLUDE_PAYLOAD_LIMIT_BYTES = 50L * 1024 * 1024

    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

    /**
     * "The file-count limit left out N files", "N+" when the count is a lower bound. Only for [n] above 0:
     * a zero count has no clause at all, even when counting stopped early (see [hasLimitLeftOut]).
     */
    fun limitLeftOutSentence(n: Int, capped: Boolean = false): String =
        if (capped) "The file-count limit left out $n+ files" else "The file-count limit left out ${files(n)}"

    /** Whether there is anything to say about files the limit left out: never for a zero count. */
    @Suppress("UNUSED_PARAMETER")
    fun hasLimitLeftOut(n: Int, capped: Boolean): Boolean = n > 0

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
            if (hasLimitLeftOut(summary.limitLeftOut, summary.limitLeftOutCapped)) {
                add(limitLeftOutSentence(summary.limitLeftOut, summary.limitLeftOutCapped))
            }
        }
        return DialogText(
            found = "Non-text files found: $n (total ${formatSize(summary.totalBytes)}).",
            estimate = "Estimated payload with them: ${formatSize(summary.effectiveEstimateBytes)}",
            paths = summary.carried.take(DIALOG_PATH_LIMIT).map { shortenMiddle(it.path) to formatSize(it.size) },
            more = if (n > DIALOG_PATH_LIMIT) "and ${n - DIALOG_PATH_LIMIT} more" else null,
            notes = notes,
            includeUnavailable = listOfNotNull(
                if (summary.effectiveEstimateBytes > INCLUDE_PAYLOAD_LIMIT_BYTES) {
                    "Include is unavailable: the payload with non-text files would be over the 50 MiB clipboard limit."
                } else {
                    null
                },
                if (ClipboardRestoreParser.headerFormatUnescapable(summary.headerFormat)) {
                    "Include is unavailable: the header format \"${summary.headerFormat}\" would make Base64 lines read as file headers."
                } else {
                    null
                }
            ).joinToString(" ").ifEmpty { null }
        )
    }

    /**
     * Extra completion-notice lines after Include (plain text, not HTML-escaped): the Base64 count, then
     * one block per skip category ([skipLines]).
     */
    fun includeNoticeLines(nonTextCopied: Int, notes: SkipNotes): List<String> =
        listOf("Non-text files copied as Base64: $nonTextCopied.") +
            SkipCategory.entries.flatMap { skipLines(it, notes.count(it), notes.names(it)) }

    /**
     * Exclude / no-dialog notice lines. Today's sentence (" N skipped: not UTF-8 text or unreadable.")
     * already carries the unreadable count, so that block drops its count line; the other categories
     * follow with theirs.
     */
    fun excludeNoticeLines(notes: SkipNotes): List<String> =
        unreadableLines(notes.count(SkipCategory.UNREADABLE), notes.names(SkipCategory.UNREADABLE)).drop(1) +
            SkipCategory.entries.filter { it != SkipCategory.UNREADABLE }
                .flatMap { skipLines(it, notes.count(it), notes.names(it)) }

    /**
     * X3: the lines under "Nothing to copy" when every selected item was skipped and the clipboard was left
     * alone. The same sentences and category blocks as the normal Exclude / no-dialog notice, one per line,
     * so the user sees why nothing was copied.
     */
    fun nothingToCopyLines(sizeSkipped: Int, notes: SkipNotes): List<String> = buildList {
        if (sizeSkipped > 0) add("$sizeSkipped skipped: size exceeded.")
        val unreadable = notes.count(SkipCategory.UNREADABLE)
        if (unreadable > 0) add("$unreadable skipped: not UTF-8 text or unreadable.")
        addAll(excludeNoticeLines(notes))
    }

    fun unreadableLines(unreadableCount: Int, unreadablePaths: List<String>): List<String> =
        skipLines(SkipCategory.UNREADABLE, unreadableCount, unreadablePaths)

    /**
     * One category: "<label>: <count>", the first [NOTICE_PATH_LIMIT] names with control characters made
     * visible, then "and N more". N is [count] minus the names shown, so the block always adds up.
     */
    fun skipLines(category: SkipCategory, count: Int, names: List<String>): List<String> = buildList {
        if (count <= 0) return@buildList
        add("${category.label}: $count")
        val shown = names.take(minOf(NOTICE_PATH_LIMIT, count))
        shown.forEach { add(shortenMiddle(PortableName.visibleName(it))) }
        if (count > shown.size) add("and ${count - shown.size} more")
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
