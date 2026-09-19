package com.github.audichuang.clipcode

/**
 * Pure assembler for the cross-tool clipboard wire format — the single source of
 * truth on the IntelliJ side for how resolved files become clipboard text.
 *
 * Byte-for-byte mirror of the VS Code sibling's `buildPayloadInternal`
 * (ClipCodeVSCode/src/clipboardFormat.ts). Any change here MUST land on that side
 * too, or cross-tool restore drifts. It has no IDE/VFS dependency so it can be
 * exercised directly by the shared golden-fixture tests.
 */
object ClipboardPayloadFormatter {
    /**
     * One entry in the payload. [content] is the raw file body; [skippedReason]
     * (when set) replaces the body with a `// File skipped: <reason>` line. Exactly
     * one of the two is meaningful — skippedReason wins, matching VS Code.
     */
    data class PayloadFile(
        val path: String,
        val content: String? = null,
        val changeType: ChangeTypeLabel? = null,
        val skippedReason: String? = null
    )

    data class Options(
        val headerFormat: String,
        val preText: String = "",
        val postText: String = "",
        val addExtraLineBetweenFiles: Boolean,
        val files: List<PayloadFile>,
        /**
         * Basename of the source root; emitted as the leading `// clipcode-root:`
         * metadata line. Null for zero/multi-root — no metadata line.
         */
        val sourceRoot: String? = null
    )

    /** Regular copy: empty pre/post wrappers still occupy a line (mirrors buildPayload). */
    fun buildPayload(options: Options): String = buildInternal(options, includeEmptyWrappers = true)

    /** Git copy: empty pre/post wrappers are omitted (mirrors buildGitPayload). */
    fun buildGitPayload(options: Options): String = buildInternal(options, includeEmptyWrappers = false)

    private fun buildInternal(options: Options, includeEmptyWrappers: Boolean): String {
        val headerFormat = options.headerFormat
        val lines = mutableListOf<String>()

        // Metadata line first (before any header) so parsers drop it as pre-header
        // text; only extractSourceRoot() reads it. Suppress it when the configured
        // header is permissive enough to parse the marker line as a file, which would
        // make it a phantom entry.
        // Empty string is treated as absent, mirroring the TS authority's truthiness
        // check (`if (options.sourceRoot)`), not just null — so the same Options
        // produce byte-identical output on both sides.
        options.sourceRoot?.takeIf { it.isNotEmpty() }?.let { sourceRoot ->
            val metaLine = ClipboardRestoreParser.SOURCE_ROOT_MARKER + sourceRoot
            if (!ClipboardRestoreParser.wouldParseAsHeader(metaLine, headerFormat)) {
                lines.add(metaLine)
            }
        }

        // Escape pre/post text too, so a header-shaped wrapper can't read back as a file.
        if (includeEmptyWrappers || options.preText.isNotEmpty()) {
            lines.add(ClipboardRestoreParser.escapeContent(options.preText, headerFormat))
        }

        for (file in options.files) {
            lines.add(
                GitClipboardFormatter.buildHeader(
                    headerFormat = headerFormat,
                    clipboardPath = file.path,
                    changeType = file.changeType
                )
            )
            // Non-empty skippedReason wins; empty/null falls through to content —
            // mirrors the TS authority's `file.skippedReason ? … : file.content ?? ''`
            // truthiness, so an empty reason doesn't emit a bare "// File skipped: ".
            val body = if (!file.skippedReason.isNullOrEmpty()) {
                "// File skipped: ${file.skippedReason}"
            } else {
                file.content ?: ""
            }
            lines.add(ClipboardRestoreParser.escapeContent(body, headerFormat))
            if (options.addExtraLineBetweenFiles) {
                lines.add("")
            }
        }

        if (includeEmptyWrappers || options.postText.isNotEmpty()) {
            // Only when there IS a footer — an empty wrapper slot needs no terminator, and
            // this keeps every postText-free payload byte-identical to the previous format.
            // Suppressed when the configured header would swallow the marker line, exactly
            // as the `clipcode-root` line is: under `// $FILE_PATH` the marker IS a valid
            // header for a file named `clipcode-end`, so emitting it would make that real
            // file unrepresentable. Without the terminator the footer glues onto the last
            // file, which payloads from before this marker already do.
            if (options.postText.isNotEmpty() &&
                !ClipboardRestoreParser.wouldParseAsHeader(ClipboardRestoreParser.POST_TEXT_MARKER, headerFormat)
            ) {
                lines.add(ClipboardRestoreParser.POST_TEXT_MARKER)
            }
            lines.add(ClipboardRestoreParser.escapeContent(options.postText, headerFormat))
        }

        return lines.joinToString("\n")
    }
}
