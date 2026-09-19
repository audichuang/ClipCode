package com.github.audichuang.clipcode

class RestorePlanBuilder(
    private val pathResolver: ClipboardPathResolver
) {
    fun build(entries: List<ClipboardRestoreParser.ParsedClipboardEntry>): RestorePlan {
        val createOperations = mutableListOf<RestorePlan.CreateOperation>()
        val deleteOperations = mutableListOf<RestorePlan.DeleteOperation>()
        val skippedOperations = mutableListOf<RestorePlan.SkippedOperation>()

        entries.forEach { entry ->
            if (entry.isDeleted) {
                when (val resolution = pathResolver.resolveDeleteTarget(entry.path)) {
                    is ClipboardPathResolver.DeleteResolution.Resolved -> {
                        deleteOperations.add(
                            RestorePlan.DeleteOperation(
                                relativePath = resolution.target.relativePath,
                                absolutePath = resolution.target.absolutePath
                            )
                        )
                    }

                    is ClipboardPathResolver.DeleteResolution.Missing -> {
                        skippedOperations.add(
                            RestorePlan.SkippedOperation(
                                rawPath = entry.path,
                                relativePath = resolution.relativePath,
                                reason = RestorePlan.SkipReason.ALREADY_ABSENT
                            )
                        )
                    }

                    is ClipboardPathResolver.DeleteResolution.Ambiguous -> {
                        skippedOperations.add(
                            RestorePlan.SkippedOperation(
                                rawPath = entry.path,
                                relativePath = resolution.relativePath,
                                reason = RestorePlan.SkipReason.AMBIGUOUS_TARGET,
                                candidates = resolution.candidates
                            )
                        )
                    }

                    is ClipboardPathResolver.DeleteResolution.Unresolved -> {
                        skippedOperations.add(
                            RestorePlan.SkippedOperation(
                                rawPath = resolution.rawPath,
                                relativePath = null,
                                reason = RestorePlan.SkipReason.UNRESOLVED_PATH
                            )
                        )
                    }
                }
            } else if (isPlaceholderBody(entry.content)) {
                skippedOperations.add(
                    RestorePlan.SkippedOperation(
                        rawPath = entry.path,
                        relativePath = null,
                        reason = RestorePlan.SkipReason.PLACEHOLDER_BODY
                    )
                )
            } else {
                when (val resolution = pathResolver.resolveWriteTarget(entry.path)) {
                    is ClipboardPathResolver.WriteResolution.Resolved -> {
                        if (Utf8Text.mustNotOverwrite(resolution.target.absolutePath)) {
                            skippedOperations.add(
                                RestorePlan.SkippedOperation(
                                    rawPath = entry.path,
                                    relativePath = resolution.target.relativePath,
                                    reason = RestorePlan.SkipReason.NON_UTF8_TARGET
                                )
                            )
                            return@forEach
                        }
                        createOperations.add(
                            RestorePlan.CreateOperation(
                                relativePath = resolution.target.relativePath,
                                absolutePath = resolution.target.absolutePath,
                                rootPath = resolution.target.rootPath,
                                content = entry.content,
                                existed = resolution.target.existed
                            )
                        )
                    }

                    is ClipboardPathResolver.WriteResolution.Ambiguous -> {
                        skippedOperations.add(
                            RestorePlan.SkippedOperation(
                                rawPath = entry.path,
                                relativePath = resolution.relativePath,
                                reason = RestorePlan.SkipReason.AMBIGUOUS_TARGET,
                                candidates = resolution.candidates
                            )
                        )
                    }

                    is ClipboardPathResolver.WriteResolution.Unresolved -> {
                        skippedOperations.add(
                            RestorePlan.SkippedOperation(
                                rawPath = resolution.rawPath,
                                relativePath = null,
                                reason = RestorePlan.SkipReason.UNRESOLVED_PATH
                            )
                        )
                    }
                }
            }
        }

        return RestorePlan(
            createOperations = createOperations,
            deleteOperations = deleteOperations,
            skippedOperations = skippedOperations,
            roots = pathResolver.roots()
        )
    }

    /**
     * The copy side substitutes a one-line comment for a file it could not embed (over the
     * size limit, or unreadable). That comment is not file content: restoring it would
     * replace the real file with a few dozen bytes. Producers: ClipboardPayloadFormatter
     * ("File skipped"), GitClipboardPayloadBuilder ("Unable to read" / "Error reading"),
     * CopyFileContentAction (size limit).
     */
    private fun isPlaceholderBody(content: String): Boolean {
        // The FIRST line, not the whole body. Every producer emits the placeholder as
        // exactly one line, so anything after it is trailing noise — and requiring a
        // single-line body meant any such noise (a configured footer, a stray line from a
        // hand-edited payload) switched the guard off and let a ~50-byte stub overwrite the
        // real file. Testing the first line is receiver-independent: no setting, no sender,
        // no tool can turn it off. The accepted cost: a real file whose FIRST line is one of these three markers is now skipped. `// File skipped: ` is a string this tool invents, but `// Unable to read file content` and `// Error reading file content` are plausible human comments — that part is a real, if narrow, false positive. asciiTrim, not String.trim(): the two stdlibs disagree on
        // U+001C-U+001F and U+FEFF, and here that decided whether a destructive write
        // happened. Mirror of restore.ts isPlaceholderBody — keep the two in step.
        val body = ClipboardRestoreParser.asciiTrim(content)
        val first = ClipboardRestoreParser.asciiTrim(body.substringBefore('\n'))
        return first.startsWith("// File skipped: ") ||
            first == "// Unable to read file content" ||
            first == "// Error reading file content"
    }
}

data class RestorePlan(
    val createOperations: List<CreateOperation>,
    val deleteOperations: List<DeleteOperation>,
    val skippedOperations: List<SkippedOperation>,
    /**
     * Every root this plan was validated against, so the executor can re-ask the
     * containment question immediately before touching the filesystem.
     *
     * Deliberately has NO default. Deriving it from the create operations — the first
     * attempt — is wrong twice over: a delete-only plan then carries no roots at all and
     * the pre-write check silently passes everything (exactly the race this check exists
     * to close), and a delete whose root has no create op is compared against the wrong
     * set and refused although plan time allowed it. Mirror of restore.ts RestorePlan.roots.
     */
    val roots: List<String>
) {
    data class CreateOperation(
        val relativePath: String,
        val absolutePath: String,
        val rootPath: String,
        val content: String,
        val existed: Boolean
    )

    data class DeleteOperation(
        val relativePath: String,
        val absolutePath: String
    )

    data class SkippedOperation(
        val rawPath: String,
        val relativePath: String?,
        val reason: SkipReason,
        val candidates: List<String> = emptyList()
    )

    enum class SkipReason {
        ALREADY_ABSENT,
        UNRESOLVED_PATH,
        AMBIGUOUS_TARGET,
        PLACEHOLDER_BODY,

        /**
         * The file on disk is not UTF-8. Writing UTF-8 over it would change its encoding
         * silently — the wire carries none, so we cannot put back what was there.
         */
        NON_UTF8_TARGET
    }
}
