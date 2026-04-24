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
            "^(?://|#|/\\*)?\\s*file:\\s*(.+?)\\s*(?:\\*/)?$",
            RegexOption.IGNORE_CASE
        )
    }

    fun parse(content: String, headerFormat: String): List<ParsedClipboardEntry> {
        val parsedEntries = mutableListOf<ParsedClipboardEntry>()
        val customRegex = Regex(toHeaderPattern(headerFormat))
        val lines = content.lines()

        var currentFilePath: String? = null
        var currentChangeTypes: Set<ChangeTypeLabel> = emptySet()
        val currentContent = StringBuilder(512)

        for (line in lines) {
            var match = customRegex.find(line)
            if (match == null || match.groups.size <= 1) {
                match = GENERIC_FILE_HEADER.find(line)
            }

            if (match != null && match.groups.size > 1) {
                if (currentFilePath != null) {
                    parsedEntries.add(
                        ParsedClipboardEntry(
                            path = currentFilePath,
                            content = currentContent.toString().trim(),
                            changeTypes = currentChangeTypes
                        )
                    )
                }

                val rawPath = match.groups[1]?.value ?: continue
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

    private fun toHeaderPattern(headerFormat: String): String =
        headerFormat
            .replace("$", "\\$")
            .replace(".", "\\.")
            .replace("*", "\\*")
            .replace("+", "\\+")
            .replace("?", "\\?")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\\\$FILE_PATH", "(.+)")
}
