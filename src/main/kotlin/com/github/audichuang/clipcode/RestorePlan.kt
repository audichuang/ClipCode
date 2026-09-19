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
            skippedOperations = skippedOperations
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
        val body = content.trim()
        if (body.contains('\n')) return false
        return body.startsWith("// File skipped: ") ||
            body == "// Unable to read file content" ||
            body == "// Error reading file content"
    }
}

data class RestorePlan(
    val createOperations: List<CreateOperation>,
    val deleteOperations: List<DeleteOperation>,
    val skippedOperations: List<SkippedOperation>
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
        PLACEHOLDER_BODY
    }
}
