package com.github.audichuang.clipcode

class ClipboardRestoreParser {
    data class ParsedClipboardEntry(
        val path: String,
        val content: String,
        val changeTypes: Set<ChangeTypeLabel> = emptySet()
    ) {
        val isDeleted: Boolean
            get() = changeTypes.contains(ChangeTypeLabel.DELETED)
    }

    companion object {
        /**
         * Matches: // file: xxx, # file: xxx, file: xxx, etc.
         */
        // The `file:` token is spelled out per-character instead of relying on a
        // case-insensitive flag. Kotlin's RegexOption.IGNORE_CASE is
        // CASE_INSENSITIVE|UNICODE_CASE, which folds the Turkish dotless i (U+0131) onto
        // `i`; JavaScript's /i explicitly refuses that fold (a non-ASCII char whose
        // canonical form is ASCII is left alone). A content line "// fıle: phantom.ts"
        // was therefore a header here and plain content in VS Code, so a Snipcode payload
        // pasted into IntelliJ was truncated and grew a phantom file. ASCII case folding
        // is all this token ever needed.
        private val GENERIC_FILE_HEADER = Regex(
            "^[$ASCII_WS]*(?:(//|#|/\\*)[$ASCII_WS]*)?[Ff][Ii][Ll][Ee]:[$ASCII_WS]*($HEADER_PATH_CHARS+?)[$ASCII_WS]*(?:\\*/)?$"
        )

        /**
         * Scheme A escape marker. Prefixed onto any content line that would parse
         * as a header so it round-trips as content, not a phantom file boundary.
         * MUST be byte-identical to the VS Code mirror (ClipCodeVSCode
         * src/clipboardFormat.ts ESCAPE_MARKER) or cross-tool restore breaks.
         */
        const val ESCAPE_MARKER = "//clipcode-esc: "

        /**
         * Leading metadata line: records the source root folder name so restore can
         * align folder levels deterministically. Sits before the first file header,
         * so parsers that don't know it drop it as pre-header text. MUST be
         * byte-identical to the VS Code mirror (clipboardFormat.ts SOURCE_ROOT_MARKER).
         */
        const val SOURCE_ROOT_MARKER = "// clipcode-root: "

        /**
         * Terminates the last file's body. Without it NOTHING on the wire says where the
         * file content stops and the configured footer starts, so the footer was
         * accumulated INTO that file — which also made a size-skipped placeholder body
         * multi-line and walked straight past the placeholder guard, overwriting a real
         * 1100-byte file with a 58-byte stub. Reconstructing the footer from the
         * RECEIVER's setting cannot work: the two tools do not share settings, so it
         * missed exactly when it mattered, and on a foreign payload it silently deleted a
         * real closing line. An explicit marker is receiver-independent. Escaped like any
         * other line when it appears in real content. MUST be byte-identical to the VS
         * Code mirror (clipboardFormat.ts POST_TEXT_MARKER).
         */
        const val POST_TEXT_MARKER = "// clipcode-end"

        /**
         * ASCII-only trim, never String.trim(). Kotlin's trim is Character.isWhitespace ∪
         * isSpaceChar and JavaScript's is the ECMAScript WhiteSpace set; they disagree on
         * U+001C-U+001F and U+FEFF. That disagreement is not cosmetic — it decided whether
         * a header path kept a trailing control char (so the two tools wrote DIFFERENT
         * filenames), whether a line counted as blank, and, through isPlaceholderBody,
         * whether a real file got overwritten. One ASCII class on both sides removes it.
         */
        private val ASCII_WS_CHARS = charArrayOf(' ', '\t', '\n', '\u000B', '\u000C', '\r')

        fun asciiTrim(value: String): String = value.trim(*ASCII_WS_CHARS)

        /** Read the source-root metadata from the first line, if present. */
        fun extractSourceRoot(clipboardText: String): String? {
            val firstLine = clipboardText.substringBefore('\n')
            if (!firstLine.startsWith(SOURCE_ROOT_MARKER)) return null
            return asciiTrim(firstLine.removePrefix(SOURCE_ROOT_MARKER)).ifEmpty { null }
        }

        /** Build side: true when [line] would parse as a file header under [headerFormat]. */
        /**
         * The ASCII whitespace class the cross-tool contract pins. Java's `\s` already means
         * exactly this, but the work-root AGENTS.md forbids relying on that: one
         * UNICODE_CHARACTER_CLASS away and it silently becomes Unicode-wide.
         */
        private const val ASCII_WS = " \\t\\n\\x0B\\f\\r"

        /**
         * What a header path may contain. Must NOT be `.`: Java's `.` also excludes U+0085
         * (NEL) while JavaScript's does not, so a path or a content line containing NEL
         * parsed as a header in one tool and not the other — losing a whole file in one
         * direction and truncating a real file in the other. Never use UNIX_LINES here: it
         * would let `.` match \r / U+2028 / U+2029, which JS's `.` does not.
         */
        private const val HEADER_PATH_CHARS = "[^\\n\\r\\u2028\\u2029]"

        fun wouldParseAsHeader(line: String, headerFormat: String): Boolean =
            findHeaderPath(line, toHeaderPattern(headerFormat)) != null

        /** Build side: escape content/pre/post lines that would parse as headers. */
        fun escapeContent(text: String, headerFormat: String): String {
            if (text.isEmpty()) return text
            val customRegex = toHeaderPattern(headerFormat)
            return text.split("\n").joinToString("\n") { line ->
                if (needsEscape(line, customRegex)) ESCAPE_MARKER + line else line
            }
        }

        /** Parse side: strip exactly one leading marker per line (inverse of escape). */
        fun unescapeContent(text: String): String =
            text.split("\n").joinToString("\n") { line ->
                if (line.startsWith(ESCAPE_MARKER)) line.removePrefix(ESCAPE_MARKER) else line
            }

        // Mirror of the VS Code joinContent: drop only the structural blank lines the
        // builder injects (empty pre/post wrappers + the addExtraLineBetweenFiles
        // separator) while preserving the file's own leading indentation, interior
        // blank lines, and trailing spaces. Replaces a blanket String.trim() so a
        // copy made in VS Code restores byte-for-byte the same way in IntelliJ.
        fun joinContent(text: String): String {
            val lines = text.split("\n")
            var start = 0
            var end = lines.size
            while (start < end && asciiTrim(lines[start]).isEmpty()) start++
            while (end > start && asciiTrim(lines[end - 1]).isEmpty()) end--
            return lines.subList(start, end).joinToString("\n")
        }

        // Escape a line if it would parse as a header (must be hidden) or already
        // starts with the marker (so unescape stays a true inverse). Skip it when
        // prefixing the marker wouldn't stop it parsing as a header anyway — a
        // degenerate headerFormat that matches everything — so we don't mark every line.
        private fun needsEscape(line: String, customRegex: Regex?): Boolean {
            if (line.startsWith(ESCAPE_MARKER)) return true
            // A real content line that IS the end marker must not terminate its own file.
            if (line == POST_TEXT_MARKER || line == POST_TEXT_MARKER + "\r") return true
            // escapeContent splits on "\n", but the parser splits on \r?\n and drops the
            // \r. Test what the PARSER will see, or a CRLF line that is a header slips
            // through unescaped and becomes a phantom file on restore. The marker is still
            // prefixed to the original line, so the payload's line endings are untouched.
            val asParsed = line.removeSuffix("\r")
            if (findHeaderPath(asParsed, customRegex) == null) return false
            return findHeaderPath(ESCAPE_MARKER + asParsed, customRegex) == null
        }

        private fun findHeaderPath(line: String, customRegex: Regex?): String? {
            val customMatch = customRegex?.matchEntire(line)
            if (customMatch != null && customMatch.groups.size > 1) {
                return customMatch.groups[1]?.value
            }

            val genericMatch = GENERIC_FILE_HEADER.matchEntire(line) ?: return null
            val prefix = genericMatch.groups[1]?.value
            val rawPath = genericMatch.groups[2]?.value ?: return null
            if (prefix == null && !isLikelyBareFileHeaderPath(rawPath)) {
                return null
            }

            return rawPath
        }

        private fun isLikelyBareFileHeaderPath(rawPath: String): Boolean {
            val path = asciiTrim(ChangeTypeLabel.stripLabels(rawPath))
            if (path.isEmpty()) return false
            if (path.startsWith("\"") || path.startsWith("'")) return false
            if (path.endsWith(",") || path.endsWith(";")) return false

            return path.contains('/') || path.contains('\\') || path.contains('.')
        }

        // Mirror of the VS Code split(/\r?\n/): line boundaries are \n and \r\n only,
        // never a lone \r. Kept separate from Kotlin's stdlib lines() on purpose.
        private val LINE_SEPARATOR = Regex("\\r?\\n")

        private fun splitLines(text: String): List<String> = text.split(LINE_SEPARATOR)

        private fun toHeaderPattern(headerFormat: String): Regex? {
            val placeholder = "\$FILE_PATH"
            val placeholderIndex = headerFormat.indexOf(placeholder)
            if (placeholderIndex < 0) return null

            val prefix = Regex.escape(headerFormat.substring(0, placeholderIndex))
            val suffix = Regex.escape(headerFormat.substring(placeholderIndex + placeholder.length))
            return Regex("^$prefix($HEADER_PATH_CHARS+?)$suffix$")
        }
    }

    fun parse(content: String, headerFormat: String): List<ParsedClipboardEntry> {
        val parsedEntries = mutableListOf<ParsedClipboardEntry>()
        val customRegex = toHeaderPattern(headerFormat)
        // Split on \r?\n only — NOT lone \r — so line boundaries match the VS Code
        // mirror exactly (clipboardFormat.ts uses split(/\r?\n/)). Kotlin's
        // String.lines() would additionally split on a bare \r, diverging on content
        // that carries lone carriage returns.
        val allLines = splitLines(content)
        // Drop a leading source-root metadata line so a permissive headerFormat can't
        // turn it into a phantom file. extractSourceRoot() reads it separately.
        val lines = if (allLines.firstOrNull()?.startsWith(SOURCE_ROOT_MARKER) == true) allLines.drop(1) else allLines

        var currentFilePath: String? = null
        var currentChangeTypes: Set<ChangeTypeLabel> = emptySet()
        val currentContent = StringBuilder(512)

        fun flush() {
            val path = currentFilePath ?: return
            parsedEntries.add(
                ParsedClipboardEntry(
                    path = path,
                    content = unescapeContent(joinContent(currentContent.toString())),
                    changeTypes = currentChangeTypes
                )
            )
            currentFilePath = null
            currentChangeTypes = emptySet()
            currentContent.clear()
        }

        for (line in lines) {
            val rawPath = findHeaderPath(line, customRegex)
            // The HEADER wins. Under a permissive format such as `// $FILE_PATH` this very
            // line is the header of a real file named `clipcode-end`, and treating it as
            // the terminator first discarded that file and its body outright. The builder
            // suppresses the marker for exactly those formats, so the two rules never
            // fight over the same line.
            if (rawPath == null && line == POST_TEXT_MARKER) {
                // Ends the CURRENT file's body — the footer follows. NOT the whole parse:
                // two payloads pasted back to back is an ordinary thing to do, and stopping
                // here dropped every file in the second one without a word.
                flush()
                continue
            }
            if (rawPath != null) {
                flush()

                currentChangeTypes = ChangeTypeLabel.extractLeadingLabels(rawPath)
                currentFilePath = ChangeTypeLabel.stripLabels(rawPath)
                currentContent.clear()
            } else if (currentFilePath != null) {
                if (currentContent.isNotEmpty()) {
                    currentContent.append('\n')
                }
                currentContent.append(line)
            }
        }

        flush()

        return parsedEntries
    }
}
