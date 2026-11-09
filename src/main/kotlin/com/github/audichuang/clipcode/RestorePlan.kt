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
            } else {
                // Before the generic placeholder guard: a non-text placeholder starts like any
                // other placeholder, which is what makes an older reader skip it. A text file
                // whose first line merely looks like one arrives with escapedPlaceholder set
                // and is restored as the text it is.
                val parsed = if (entry.escapedPlaceholder) null else NonText.parseBody(entry.content)
                if (parsed is NonText.DecodeResult.Corrupt) {
                    skippedOperations.add(
                        RestorePlan.SkippedOperation(
                            rawPath = entry.path,
                            relativePath = null,
                            reason = RestorePlan.SkipReason.CORRUPT_NON_TEXT
                        )
                    )
                    return@forEach
                }
                val nonTextBytes = (parsed as? NonText.DecodeResult.Ok)?.bytes
                if (nonTextBytes == null && !entry.escapedPlaceholder && isPlaceholderBody(entry.content)) {
                    skippedOperations.add(
                        RestorePlan.SkippedOperation(
                            rawPath = entry.path,
                            relativePath = null,
                            reason = RestorePlan.SkipReason.PLACEHOLDER_BODY
                        )
                    )
                    return@forEach
                }
                when (val resolution = pathResolver.resolveWriteTarget(entry.path)) {
                    is ClipboardPathResolver.WriteResolution.Resolved -> {
                        val target = resolution.target
                        val path = java.nio.file.Path.of(target.absolutePath)
                        fun skip(reason: RestorePlan.SkipReason) {
                            skippedOperations.add(
                                RestorePlan.SkippedOperation(
                                    rawPath = entry.path,
                                    relativePath = target.relativePath,
                                    reason = reason
                                )
                            )
                        }
                        // A directory (or another non-file) at the target skips this row only.
                        if (java.nio.file.Files.exists(path) && !java.nio.file.Files.isRegularFile(path)) {
                            skip(RestorePlan.SkipReason.TARGET_NOT_FILE)
                            return@forEach
                        }
                        // A file where a parent folder must be: nothing can be created below it.
                        if (parentIsFile(path)) {
                            skip(RestorePlan.SkipReason.PARENT_NOT_FOLDER)
                            return@forEach
                        }
                        // Raw bytes are written as is: there is no encoding to preserve.
                        val isLink = java.nio.file.Files.isSymbolicLink(path)
                        // A symlink is replaced, not read through: the encoding of what it points at
                        // says nothing about what will be written in its place.
                        if (nonTextBytes == null && !isLink && Utf8Text.mustNotOverwrite(target.absolutePath)) {
                            skip(RestorePlan.SkipReason.NON_UTF8_TARGET)
                            return@forEach
                        }
                        val existed = target.existed || java.nio.file.Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        createOperations.add(
                            RestorePlan.CreateOperation(
                                relativePath = target.relativePath,
                                absolutePath = target.absolutePath,
                                rootPath = target.rootPath,
                                content = if (nonTextBytes != null) "" else entry.content,
                                existed = existed,
                                nonTextBytes = nonTextBytes,
                                identical = nonTextBytes != null && existed && !isLink && holdsBytes(path, nonTextBytes)
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

        removeCaseCollisions(createOperations, skippedOperations)

        return RestorePlan(
            createOperations = createOperations,
            deleteOperations = deleteOperations,
            skippedOperations = skippedOperations,
            roots = pathResolver.roots()
        )
    }

    /**
     * Two entries that map to the same file only by letter case (`IMG.png` / `img.png`), on
     * macOS only by Unicode normalization (`caf\u00e9` NFC / NFD), or through a symlinked parent
     * folder (`real/x.png` / `link/x.png` with `link -> real`) are the same file on that file
     * system: writing both would silently let the second replace the first. Every colliding op is
     * dropped and skipped. Whether the file system folds case or normalization is probed on the
     * op's own root, never assumed from the OS. Exact duplicates (same string) keep their
     * create-then-overwrite behaviour.
     */
    private fun removeCaseCollisions(
        creates: MutableList<RestorePlan.CreateOperation>,
        skipped: MutableList<RestorePlan.SkippedOperation>
    ) {
        val identities = HashMap<String, String>()
        fun identity(op: RestorePlan.CreateOperation) = identities.getOrPut(op.absolutePath) { targetIdentity(op.absolutePath) }
        // Candidates: equal under the widest folding (NFC + lower case) yet spelled differently.
        val groups = creates.groupBy { foldKey(identity(it), nfc = true, lower = true) }
            .values.filter { group -> group.map { it.absolutePath }.distinct().size > 1 }
        if (groups.isEmpty()) return
        val folding = HashMap<String, Pair<Boolean, Boolean>>()
        val colliding = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<RestorePlan.CreateOperation, Boolean>())
        groups.forEach { group ->
            // Each op is keyed by what ITS root folds; only spellings that really meet collide.
            group.groupBy { op ->
                val (lower, nfc) = folding.getOrPut(op.rootPath) {
                    val root = java.nio.file.Path.of(op.rootPath)
                    isCaseInsensitiveFs(root) to foldsNormalization(root)
                }
                foldKey(identity(op), nfc = nfc, lower = lower)
            }.values.filter { it.map { op -> op.absolutePath }.distinct().size > 1 }.forEach { colliding.addAll(it) }
        }
        if (colliding.isEmpty()) return
        // Skip rows keep payload order, so replay the original list order.
        creates.filter { it in colliding }.forEach {
            skipped.add(
                RestorePlan.SkippedOperation(
                    rawPath = it.relativePath,
                    relativePath = it.relativePath,
                    reason = RestorePlan.SkipReason.TARGET_COLLISION
                )
            )
        }
        creates.removeAll { it in colliding }
    }

    /**
     * The file [absolutePath] really names: its nearest existing ancestor folder resolved through
     * symlinks, with the missing folders and the file name put back. The file itself is not resolved:
     * a link AT the target is replaced, never written through. Mirror of OpenIDEA
     * `canonical_target_identity`, minus that last step. The path as given when nothing resolves.
     */
    private fun targetIdentity(absolutePath: String): String {
        val path = try {
            java.nio.file.Path.of(absolutePath)
        } catch (_: java.nio.file.InvalidPathException) {
            return absolutePath
        }
        val name = path.fileName ?: return absolutePath
        var existing = path.parent ?: return absolutePath
        val missing = ArrayDeque<String>()
        while (!java.nio.file.Files.exists(existing)) {
            missing.addFirst(existing.fileName?.toString() ?: return absolutePath)
            existing = existing.parent ?: return absolutePath
        }
        var real = try {
            existing.toRealPath()
        } catch (_: java.io.IOException) {
            return absolutePath
        }
        missing.forEach { real = real.resolve(it) }
        return real.resolve(name.toString()).toString()
    }

    private fun foldKey(path: String, nfc: Boolean, lower: Boolean): String {
        var key = path
        if (nfc) key = java.text.Normalizer.normalize(key, java.text.Normalizer.Form.NFC)
        if (lower) key = key.lowercase(java.util.Locale.ROOT)
        return key
    }

    /**
     * Whether the file system holding [root] resolves an NFC and an NFD spelling of one name to
     * the same file (APFS and HFS+ do). Probes with a real file; falls back to "the OS is macOS"
     * only when the probe cannot be made.
     */
    private fun foldsNormalization(root: java.nio.file.Path): Boolean {
        val id = "${ProcessHandle.current().pid()}-${System.nanoTime()}"
        val nfc = java.text.Normalizer.normalize("clipcode-nfc-probe-\u00e9-$id", java.text.Normalizer.Form.NFC)
        val nfd = java.text.Normalizer.normalize(nfc, java.text.Normalizer.Form.NFD)
        val probe = root.resolve(nfc)
        return try {
            java.nio.file.Files.createFile(probe)
            try {
                java.nio.file.Files.exists(root.resolve(nfd))
            } finally {
                java.nio.file.Files.deleteIfExists(probe)
            }
        } catch (_: java.io.IOException) {
            System.getProperty("os.name").orEmpty().lowercase(java.util.Locale.ROOT).contains("mac")
        }
    }

    /**
     * Whether the file system holding [root] treats names that differ only by ASCII letter case
     * as one file. Looks at an existing entry first; otherwise makes (and removes) a probe file.
     * Falls back to the platform default only when neither is possible.
     */
    private fun isCaseInsensitiveFs(root: java.nio.file.Path): Boolean {
        fun swap(name: String): String? {
            if (name.none { it in 'a'..'z' || it in 'A'..'Z' }) return null
            return name.map { c ->
                when (c) {
                    in 'a'..'z' -> c.uppercaseChar()
                    in 'A'..'Z' -> c.lowercaseChar()
                    else -> c
                }
            }.joinToString("")
        }
        fun sameFile(a: java.nio.file.Path, b: java.nio.file.Path): Boolean? = try {
            if (!java.nio.file.Files.exists(b, java.nio.file.LinkOption.NOFOLLOW_LINKS)) false
            else java.nio.file.Files.isSameFile(a, b)
        } catch (_: java.io.IOException) {
            null
        }
        try {
            java.nio.file.Files.newDirectoryStream(root).use { entries ->
                for (entry in entries) {
                    val swapped = swap(entry.fileName.toString()) ?: continue
                    sameFile(entry, root.resolve(swapped))?.let { return it }
                }
            }
            val id = "${ProcessHandle.current().pid()}-${System.nanoTime()}"
            val probe = root.resolve(".clipcode-case-probe-$id")
            java.nio.file.Files.createFile(probe)
            try {
                return sameFile(probe, root.resolve(".CLIPCODE-CASE-PROBE-$id")) ?: platformFoldsCase()
            } finally {
                java.nio.file.Files.deleteIfExists(probe)
            }
        } catch (_: java.io.IOException) {
            return platformFoldsCase()
        }
    }

    private fun platformFoldsCase(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase(java.util.Locale.ROOT)
        return os.contains("win") || os.contains("mac")
    }

    /** Nearest existing ancestor is not a directory: a file lies where a parent folder is needed. */
    private fun parentIsFile(path: java.nio.file.Path): Boolean {
        var ancestor = path.parent
        while (ancestor != null) {
            if (java.nio.file.Files.exists(ancestor)) return !java.nio.file.Files.isDirectory(ancestor)
            ancestor = ancestor.parent
        }
        return false
    }

    private fun holdsBytes(path: java.nio.file.Path, bytes: ByteArray): Boolean = try {
        java.nio.file.Files.isRegularFile(path) &&
            java.nio.file.Files.size(path) == bytes.size.toLong() &&
            java.nio.file.Files.readAllBytes(path).contentEquals(bytes)
    } catch (_: java.io.IOException) {
        false
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
        /** Empty for a non-text op: the Base64 never travels in here. */
        val content: String,
        val existed: Boolean,
        /** The decoded bytes of a non-text file, written as is; null for a text op. */
        val nonTextBytes: ByteArray? = null,
        /** Non-text only: the target already holds exactly these bytes (needs no write or permission). */
        val identical: Boolean = false
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
        NON_UTF8_TARGET,

        /** A non-text entry whose Base64 does not decode, or whose size or SHA-256 does not match. */
        CORRUPT_NON_TEXT,

        /** The target path is a folder (or another non-regular file). */
        TARGET_NOT_FILE,

        /** An existing file lies where a parent folder is needed. */
        PARENT_NOT_FOLDER,

        /** Two entries map to the same file on a case-insensitive file system. */
        TARGET_COLLISION
    }
}
