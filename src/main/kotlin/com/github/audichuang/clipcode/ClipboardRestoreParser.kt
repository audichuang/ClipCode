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
        private val GENERIC_FILE_HEADER = Regex(
            "^\\s*(?:(//|#|/\\*)\\s*)?file:\\s*(.+?)\\s*(?:\\*/)?$",
            RegexOption.IGNORE_CASE
        )
    }

    fun parse(content: String, headerFormat: String): List<ParsedClipboardEntry> {
        val parsedEntries = mutableListOf<ParsedClipboardEntry>()
        val customRegex = toHeaderPattern(headerFormat)?.let(::Regex)
        val lines = content.lines()

        var currentFilePath: String? = null
        var currentChangeTypes: Set<ChangeTypeLabel> = emptySet()
        val currentContent = StringBuilder(512)

        for (line in lines) {
            val rawPath = findHeaderPath(line, customRegex)
            if (rawPath != null) {
                if (currentFilePath != null) {
                    parsedEntries.add(
                        ParsedClipboardEntry(
                            path = currentFilePath,
                            content = currentContent.toString().trim(),
                            changeTypes = currentChangeTypes
                        )
                    )
                }

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

        if (currentFilePath != null) {
            parsedEntries.add(
                ParsedClipboardEntry(
                    path = currentFilePath,
                    content = currentContent.toString().trim(),
                    changeTypes = currentChangeTypes
                )
            )
        }

        return parsedEntries
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
        val path = ChangeTypeLabel.stripLabels(rawPath).trim()
        if (path.isBlank()) return false
        if (path.startsWith("\"") || path.startsWith("'")) return false
        if (path.endsWith(",") || path.endsWith(";")) return false

        return path.contains('/') || path.contains('\\') || path.contains('.')
    }

    private fun toHeaderPattern(headerFormat: String): String? {
        val placeholder = "\$FILE_PATH"
        val placeholderIndex = headerFormat.indexOf(placeholder)
        if (placeholderIndex < 0) return null

        val prefix = Regex.escape(headerFormat.substring(0, placeholderIndex))
        val suffix = Regex.escape(headerFormat.substring(placeholderIndex + placeholder.length))
        return "^$prefix(.+?)$suffix$"
    }
}
