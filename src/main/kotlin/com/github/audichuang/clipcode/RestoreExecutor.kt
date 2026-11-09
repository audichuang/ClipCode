package com.github.audichuang.clipcode

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import java.util.UUID
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class RestoreExecutor internal constructor(
    private val project: Project,
    /** Test seam: runs with the temporary file right after it is created, before any byte goes in. */
    private val tempCreatedHook: (java.nio.file.Path) -> Unit = {},
    /**
     * Test seam: runs while a symlink at the target is being replaced, after the new bytes are in
     * the temporary file and before the rename. Throwing here stands in for a failed write. Kept
     * last so a trailing lambda (`RestoreExecutor(project) { throw ... }`) binds here.
     */
    private val linkReplaceWriteHook: (java.nio.file.Path) -> Unit = {}
) {
    constructor(project: Project) : this(project, {}, {})

    data class ExecutionResult(
        val createdCount: Int,
        val overwrittenCount: Int,
        val skippedExistingCount: Int,
        val deletedCount: Int,
        val errors: List<String>,
        val cancelled: Boolean = false,
        /** Non-text targets that already held the decoded bytes: not written. */
        val unchangedCount: Int = 0
    )

    /**
     * Containment, re-asked immediately before the filesystem is touched. The planner
     * already checked it, but the user has clicked through modal dialogs since and an
     * ordinary directory can have become a link to somewhere outside the project in
     * between. Mirror of the TypeScript executor's pre-write check.
     */
    private fun recheck(plan: RestorePlan, absolutePath: String): Boolean =
        !ClipboardPathResolver.escapesRoots(plan.roots, absolutePath)

    fun collectExistingCreatePaths(plan: RestorePlan): List<String> =
        plan.createOperations.filter { it.existed && !it.identical }.map { it.relativePath }

    fun execute(
        plan: RestorePlan,
        overwriteExisting: Boolean,
        skipExisting: Boolean,
        indicator: ProgressIndicator = ProgressManager.getInstance().progressIndicator ?: EmptyProgressIndicator()
    ): ExecutionResult {
        var createdCount = 0
        var overwrittenCount = 0
        var skippedExistingCount = 0
        var deletedCount = 0
        var unchangedCount = 0
        val errors = mutableListOf<String>()

        val total = plan.createOperations.size + plan.deleteOperations.size
        var cursor = 0
        var cancelled = false
        val groupId = UUID.randomUUID().toString()
        while (cursor < total && !cancelled) {
            if (indicator.isCanceled || project.isDisposed) { cancelled = true; break }
            try {
                val batch = Runnable {
                    val end = minOf(cursor + 32, total)
                    // System-independent, because RestoreVcsIgnoreProvider matches FilePath.path,
                    // which always uses '/'. The plan's absolutePath is NATIVE, so on Windows
                    // `C:\x\a.kt` never equalled `C:/x/a.kt`: every restored file still raised
                    // the "add to Git?" prompt and every delete the removal one — invisible on
                    // macOS and Linux, where the two spellings coincide.
                    val paths = (cursor until end).mapTo(hashSetOf()) { index ->
                        com.intellij.openapi.util.io.FileUtil.toSystemIndependentName(
                            plan.createOperations.getOrNull(index)?.absolutePath
                                ?: plan.deleteOperations[index - plan.createOperations.size].absolutePath
                        )
                    }
                    val previous = project.getUserData(RestoreVcsIgnoreProvider.PATHS)
                    project.putUserData(RestoreVcsIgnoreProvider.PATHS, paths)
                    val replacedLinks = mutableListOf<java.nio.file.Path>()
                    try {
                        CommandProcessor.getInstance().allowMergeGlobalCommands {
                            WriteCommandAction.writeCommandAction(project).withName("Restore Files from Clipboard")
                                .withGroupId(groupId).withGlobalUndo().run<RuntimeException> {
                                    val start = System.nanoTime()
                                    // ponytail: cooperative 8 ms budget; one slow filesystem operation cannot be preempted.
                                    while (cursor < end) {
                                        if (indicator.isCanceled || project.isDisposed) { cancelled = true; break }
                                        val create = plan.createOperations.getOrNull(cursor)
                                        val delete = if (create == null) plan.deleteOperations[cursor - plan.createOperations.size] else null
                                        try {
                                            if (create != null && !recheck(plan, create.absolutePath)) {
                                                errors.add("${create.relativePath}: unsafe path")
                                                cursor++
                                                continue
                                            }
                                            if (delete != null && !recheck(plan, delete.absolutePath)) {
                                                errors.add("${delete.relativePath}: unsafe path")
                                                cursor++
                                                continue
                                            }
                                            // A symlink at the target is never written through: with
                                            // overwrite consent the LINK is replaced by a regular file,
                                            // without it the row is skipped (mirror of OpenIDEA's
                                            // temp-file + rename write).
                                            if (create != null && isSymlink(create.absolutePath)) {
                                                if (skipExisting || !overwriteExisting || linkTargetIsDirectory(create.absolutePath)) {
                                                    skippedExistingCount++
                                                } else {
                                                    val link = java.nio.file.Path.of(create.absolutePath)
                                                    replaceSymlink(link, create.nonTextBytes ?: create.content.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                                    replacedLinks.add(link)
                                                    overwrittenCount++
                                                }
                                            } else if (create?.nonTextBytes != null) {
                                                when (writeNonText(create, create.nonTextBytes, overwriteExisting, skipExisting)) {
                                                    NonTextOutcome.CREATED -> createdCount++
                                                    NonTextOutcome.OVERWRITTEN -> overwrittenCount++
                                                    NonTextOutcome.UNCHANGED -> unchangedCount++
                                                    NonTextOutcome.SKIPPED_EXISTING -> skippedExistingCount++
                                                }
                                            } else if (create != null) {
                                                val existingFile = findFile(create.absolutePath)
                                                when {
                                                    existingFile != null && existingFile.isDirectory -> skippedExistingCount++
                                                    existingFile != null && skipExisting -> skippedExistingCount++
                                                    // Re-take the encoding verdict too: the plan judged the
                                                    // bytes that were there THEN, and the file may have been
                                                    // replaced while the dialogs were open.
                                                    existingFile != null && overwriteExisting &&
                                                        Utf8Text.mustNotOverwrite(create.absolutePath) -> skippedExistingCount++
                                                    existingFile != null && overwriteExisting -> {
                                                        overwriteFile(existingFile, create.content)
                                                        overwrittenCount++
                                                    }
                                                    existingFile != null -> skippedExistingCount++
                                                    else -> {
                                                        val file = createFile(create.rootPath, create.relativePath)
                                                        // A file WE create is UTF-8. Left on the project's
                                                        // inherited charset it was written as e.g. Big5, and the
                                                        // copy guard would then refuse the very file this restore
                                                        // had just produced. saveUtf8 does the same for overwrites.
                                                        saveUtf8(file, create.content)
                                                        createdCount++
                                                    }
                                                }
                                            } else if (delete != null) {
                                                val target = findFile(delete.absolutePath)
                                                if (target != null && target.exists() && !target.isDirectory) {
                                                    // Populate VFS content so native deletion Undo can restore unread files.
                                                    target.contentsToByteArray()
                                                    target.delete(this@RestoreExecutor)
                                                    deletedCount++
                                                }
                                            }
                                        } catch (e: ProcessCanceledException) {
                                            throw e
                                        } catch (e: Exception) {
                                            errors.add("${create?.relativePath ?: delete?.relativePath}: ${userErrorText(e)}")
                                        }
                                        cursor++
                                        if (System.nanoTime() - start >= 8_000_000) break
                                    }
                                }
                        }
                        // Outside the command on purpose: the refresh sees the link vanish and a
                        // file appear, and inside a command FileUndoProvider records that external
                        // change as non-undoable, which wiped the whole restore from the undo stack.
                        replacedLinks.forEach(::refreshEntry)
                    } finally {
                        project.putUserData(RestoreVcsIgnoreProvider.PATHS, previous)
                    }
                }
                val app = ApplicationManager.getApplication()
                if (app.isDispatchThread) batch.run() else app.invokeAndWait(batch)
            } catch (e: ProcessCanceledException) {
                cancelled = true
            }
            indicator.fraction = if (total == 0) 1.0 else cursor.toDouble() / total
        }

        return ExecutionResult(
            createdCount = createdCount,
            overwrittenCount = overwrittenCount,
            skippedExistingCount = skippedExistingCount,
            deletedCount = deletedCount,
            errors = errors,
            cancelled = cancelled,
            unchangedCount = unchangedCount
        )
    }

    private enum class NonTextOutcome { CREATED, OVERWRITTEN, UNCHANGED, SKIPPED_EXISTING }

    /**
     * Writes decoded non-text bytes. Never goes through a Document or the UTF-8 charset switch
     * (there is no text to encode), and never applies the non-UTF-8 overwrite guard (the bytes
     * are written as is). The target is re-read right before writing: the plan's "identical"
     * verdict is from before the dialogs.
     */
    private fun writeNonText(
        create: RestorePlan.CreateOperation,
        bytes: ByteArray,
        overwriteExisting: Boolean,
        skipExisting: Boolean
    ): NonTextOutcome {
        val existing = findFile(create.absolutePath)
        if (existing == null) {
            val file = createFile(create.rootPath, create.relativePath)
            file.setBinaryContent(bytes)
            return NonTextOutcome.CREATED
        }
        if (existing.isDirectory) return NonTextOutcome.SKIPPED_EXISTING
        if (existing.length == bytes.size.toLong() && existing.contentsToByteArray().contentEquals(bytes)) {
            return NonTextOutcome.UNCHANGED
        }
        if (skipExisting || !overwriteExisting) return NonTextOutcome.SKIPPED_EXISTING
        val before = existing.contentsToByteArray()
        writeRaw(existing) { existing.setBinaryContent(bytes) }
        com.intellij.openapi.command.undo.UndoManager.getInstance(project).undoableActionPerformed(
            object : com.intellij.openapi.command.undo.BasicUndoableAction(existing) {
                override fun undo() { writeRaw(existing) { existing.setBinaryContent(before) } }
                override fun redo() { writeRaw(existing) { existing.setBinaryContent(bytes) } }
            }
        )
        return NonTextOutcome.OVERWRITTEN
    }

    /**
     * Every byte this restore writes is UTF-8. VfsUtil.saveText and FileDocumentManager both
     * encode with the FILE's charset — the project default (Big5/GBK on many CJK setups) for a
     * file we just created, or whatever the IDE decided for an existing one — and Java's encoder
     * silently substitutes '?' for what it cannot represent. That mangled CJK text and dropped
     * '€' while reporting success; an ASCII file mapped to windows-1252 was overwritten as
     * `63 61 66 E9` for "café", leaving bytes the copy guard would then refuse to read back.
     * The payload carries no encoding, so UTF-8 is the only thing a write can mean. Only files
     * that ARE already non-UTF-8 are protected, and those never reach a write
     * (Utf8Text.mustNotOverwrite).
     */
    private fun saveUtf8(file: VirtualFile, content: String) {
        if (file.charset != java.nio.charset.StandardCharsets.UTF_8) {
            file.charset = java.nio.charset.StandardCharsets.UTF_8
        }
        VfsUtil.saveText(file, content)
    }

    private fun overwriteFile(file: VirtualFile, content: String) {
        val manager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
        val document = manager.getDocument(file)
        // Both branches write UTF-8: saveDocument encodes with the file's charset just as
        // VfsUtil.saveText does, so the document path needs the same switch.
        if (file.charset != java.nio.charset.StandardCharsets.UTF_8) {
            file.charset = java.nio.charset.StandardCharsets.UTF_8
        }
        if (document != null && file.isWritable && '\r' !in content && file.detectedLineSeparator?.contains('\r') != true) {
            document.setText(content)
            manager.saveDocument(document)
        } else {
            val before = file.contentsToByteArray()
            val unsavedText = document?.takeIf { manager.isDocumentUnsaved(it) }?.text
            writeRaw(file) { saveUtf8(file, content) }
            com.intellij.openapi.command.undo.UndoManager.getInstance(project).undoableActionPerformed(
                object : com.intellij.openapi.command.undo.BasicUndoableAction(file) {
                    override fun undo() {
                        writeRaw(file) {
                            file.setBinaryContent(before)
                            if (unsavedText != null) document?.setText(unsavedText)
                        }
                    }
                    override fun redo() { writeRaw(file) { saveUtf8(file, content) } }
                }
            )
        }
    }

    private fun isSymlink(path: String): Boolean = java.nio.file.Files.isSymbolicLink(java.nio.file.Path.of(path))

    private fun linkTargetIsDirectory(path: String): Boolean = java.nio.file.Files.isDirectory(java.nio.file.Path.of(path))

    /**
     * Replaces the symlink at [link] with a regular file holding [bytes]. The new file is written
     * beside the link and renamed over it, so a failed write leaves the link exactly as it was
     * (deleting first and then writing left the path empty, or holding a 0-byte file). Undo puts
     * the LINK back: LocalHistory records no link target, so undoing a VFS delete of a link
     * recreated a regular file instead. The caller refreshes the VFS after the command.
     */
    private fun replaceSymlink(link: java.nio.file.Path, bytes: ByteArray) {
        val linkText = java.nio.file.Files.readSymbolicLink(link)
        writeByRename(link, bytes)
        // Anchored on the folder, whose VirtualFile stays valid while the entry flips between a
        // link and a regular file; undo and redo work by path.
        val folder = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(link.parent)
            ?: throw java.nio.file.NoSuchFileException(link.parent.toString())
        com.intellij.openapi.command.undo.UndoManager.getInstance(project).undoableActionPerformed(
            object : com.intellij.openapi.command.undo.GlobalUndoableAction(folder) {
                override fun undo() {
                    restoreSymlink(link, linkText)
                    refreshEntry(link)
                }
                override fun redo() {
                    writeByRename(link, bytes)
                    refreshEntry(link)
                }
            }
        )
    }

    /**
     * Writes [bytes] to a fresh file in [target]'s folder and renames it over [target]. On POSIX
     * the temporary file is created 0600 in the same call that creates it, so no other process can
     * open it at a wider mode; it gets the mode a new file gets in that folder (0666 less the umask,
     * read off an empty probe file that is deleted at once) only after the bytes are in, right
     * before the rename. It is removed when anything fails, so the entry at [target] is untouched
     * unless the rename happens.
     */
    private fun writeByRename(target: java.nio.file.Path, bytes: ByteArray) {
        val newFileMode = newFileModeIn(target.parent)
        val temp = target.resolveSibling(".clipcode-write.${UUID.randomUUID()}")
        val createAttributes: Array<java.nio.file.attribute.FileAttribute<*>> = if (newFileMode == null) emptyArray() else arrayOf(
            java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
            )
        )
        val channel = java.nio.channels.FileChannel.open(
            temp,
            setOf(java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE),
            *createAttributes
        )
        try {
            channel.use {
                tempCreatedHook(temp)
                val buffer = java.nio.ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            linkReplaceWriteHook(target)
            if (newFileMode != null) java.nio.file.Files.setPosixFilePermissions(temp, newFileMode)
            moveReplacing(temp, target)
        } catch (e: Throwable) {
            java.nio.file.Files.deleteIfExists(temp)
            throw e
        }
    }

    /**
     * The mode a new file gets in [folder] (0666 less the umask), or null when the store has no
     * POSIX permissions. Read off an empty probe file, so no payload byte ever sits in a file at
     * that mode before it is final.
     */
    private fun newFileModeIn(folder: java.nio.file.Path): Set<java.nio.file.attribute.PosixFilePermission>? {
        if (!java.nio.file.Files.getFileStore(folder).supportsFileAttributeView("posix")) return null
        val probe = folder.resolve(".clipcode-write.${UUID.randomUUID()}")
        java.nio.file.Files.createFile(probe)
        try {
            return java.nio.file.Files.getPosixFilePermissions(probe)
        } finally {
            java.nio.file.Files.deleteIfExists(probe)
        }
    }

    /** Undo of [replaceSymlink]: a link with the original text, renamed over the regular file. */
    private fun restoreSymlink(link: java.nio.file.Path, linkText: java.nio.file.Path) {
        val temp = link.resolveSibling(".clipcode-write.${UUID.randomUUID()}")
        java.nio.file.Files.createSymbolicLink(temp, linkText)
        try {
            moveReplacing(temp, link)
        } catch (e: Throwable) {
            java.nio.file.Files.deleteIfExists(temp)
            throw e
        }
    }

    private fun moveReplacing(source: java.nio.file.Path, target: java.nio.file.Path) {
        try {
            java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** The cached VFS node was a link: refresh the folder's children and the entry itself. */
    private fun refreshEntry(path: java.nio.file.Path) {
        val fs = LocalFileSystem.getInstance()
        fs.refreshAndFindFileByNioFile(path.parent)?.let { VfsUtil.markDirtyAndRefresh(false, false, true, it) }
        fs.refreshAndFindFileByNioFile(path)?.let { VfsUtil.markDirtyAndRefresh(false, false, false, it) }
    }

    private fun <T> withWritable(file: VirtualFile, write: () -> T): T =
        withWritablePath(java.nio.file.Path.of(file.path), FileWriteAccess.Nio, { file.refresh(false, false) }, write)

    private fun writeRaw(file: VirtualFile, write: () -> Unit) {
        withWritable(file) { writeRawUnguarded(file, write) }
    }

    private fun writeRawUnguarded(file: VirtualFile, write: () -> Unit) {
        val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getCachedDocument(file)
        if (document == null) write()
        else com.intellij.openapi.command.undo.UndoUtil.disableUndoIn(document) { write() }
    }

    private fun createFile(rootPath: String, relativePath: String): VirtualFile {
        val rootDir = findFile(rootPath)
            ?: throw IllegalStateException("Unable to resolve root directory: $rootPath")
        if (!rootDir.isDirectory) {
            throw IllegalStateException("Root path is not a directory: $rootPath")
        }

        val pathParts = relativePath.split("/")
        val fileName = pathParts.last()
        val directoryPath = pathParts.dropLast(1)

        var currentDir = rootDir
        directoryPath.forEach { dirName ->
            val existingDir = currentDir.findChild(dirName)
            currentDir = if (existingDir != null && existingDir.isDirectory) {
                existingDir
            } else {
                currentDir.createChildDirectory(this, dirName)
            }
        }

        return currentDir.findChild(fileName) ?: currentDir.createChildData(this, fileName)
    }

    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
}

/** The file-mode operations [withWritablePath] needs; a seam so a non-POSIX store can be faked. */
internal interface FileWriteAccess {
    fun isWritable(path: java.nio.file.Path): Boolean
    /** Null when the store has no POSIX view. */
    fun posixPermissions(path: java.nio.file.Path): Set<java.nio.file.attribute.PosixFilePermission>?
    fun setPosixPermissions(path: java.nio.file.Path, permissions: Set<java.nio.file.attribute.PosixFilePermission>)
    /** The DOS read-only attribute; null when the store has no DOS view. */
    fun readOnlyAttribute(path: java.nio.file.Path): Boolean?
    fun setReadOnlyAttribute(path: java.nio.file.Path, readOnly: Boolean)

    object Nio : FileWriteAccess {
        override fun isWritable(path: java.nio.file.Path) = java.nio.file.Files.isWritable(path)
        override fun posixPermissions(path: java.nio.file.Path) =
            if (java.nio.file.Files.getFileStore(path).supportsFileAttributeView("posix")) java.nio.file.Files.getPosixFilePermissions(path) else null
        override fun setPosixPermissions(path: java.nio.file.Path, permissions: Set<java.nio.file.attribute.PosixFilePermission>) {
            java.nio.file.Files.setPosixFilePermissions(path, permissions)
        }
        override fun readOnlyAttribute(path: java.nio.file.Path): Boolean? =
            if (java.nio.file.Files.getFileStore(path).supportsFileAttributeView("dos")) {
                java.nio.file.Files.readAttributes(path, java.nio.file.attribute.DosFileAttributes::class.java).isReadOnly
            } else null
        override fun setReadOnlyAttribute(path: java.nio.file.Path, readOnly: Boolean) {
            java.nio.file.Files.setAttribute(path, "dos:readonly", readOnly)
        }
    }
}

/**
 * A read-only target follows the overwrite option like any other existing file: the write
 * window makes it writable, then the original mode is put back. The VFS refuses to write a
 * file the OS marks read-only, so without this an overwrite consent became a failure.
 */
internal fun <T> withWritablePath(
    path: java.nio.file.Path,
    access: FileWriteAccess,
    afterModeChange: () -> Unit,
    write: () -> T
): T {
    if (access.isWritable(path)) return write()
    // Record the original state on every store and put back exactly that. On Windows a file can
    // be unwritable through its ACL with the DOS read-only attribute clear; clearing nothing and
    // then setting read-only in `finally` added an attribute the file never had.
    val savedMode = access.posixPermissions(path)
    val wasReadOnly = if (savedMode == null) access.readOnlyAttribute(path) == true else false
    when {
        savedMode != null -> access.setPosixPermissions(path, savedMode + java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)
        wasReadOnly -> access.setReadOnlyAttribute(path, false)
        else -> return write()
    }
    afterModeChange()
    try {
        return write()
    } finally {
        if (savedMode != null) access.setPosixPermissions(path, savedMode) else access.setReadOnlyAttribute(path, true)
        afterModeChange()
    }
}

/** Suppress VCS add/remove prompts only for paths in the current restore command. */
class RestoreVcsIgnoreProvider : com.intellij.openapi.vcs.VcsFileListenerIgnoredFilesProvider {
    override fun isAdditionIgnored(project: Project, filePath: com.intellij.openapi.vcs.FilePath): Boolean =
        project.getUserData(PATHS)?.contains(filePath.path) == true

    override fun isDeletionIgnored(project: Project, filePath: com.intellij.openapi.vcs.FilePath): Boolean =
        isAdditionIgnored(project, filePath)

    companion object {
        internal val PATHS = com.intellij.openapi.util.Key.create<Set<String>>("clipcode.restore.vcs.paths")
    }
}

/**
 * User-facing text for a write failure, in place of the raw OS message (which reads like
 * "Permission denied (os error 13)" or leaks absolute paths).
 */
internal fun userErrorText(e: Throwable): String {
    val message = e.message.orEmpty()
    return when {
        e is java.nio.file.AccessDeniedException || message.contains("Permission denied", ignoreCase = true) ->
            "Folder is not writable"
        e is java.nio.file.NoSuchFileException -> "File or folder not found"
        e is java.nio.file.FileAlreadyExistsException -> "A file or folder already exists at that path"
        e is java.nio.file.NotDirectoryException || message.contains("Not a directory", ignoreCase = true) ->
            "A file is in the way of its parent folder"
        else -> "Could not apply the change"
    }
}
