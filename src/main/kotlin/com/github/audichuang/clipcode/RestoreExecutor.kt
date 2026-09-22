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

class RestoreExecutor(
    private val project: Project
) {
    data class ExecutionResult(
        val createdCount: Int,
        val overwrittenCount: Int,
        val skippedExistingCount: Int,
        val deletedCount: Int,
        val errors: List<String>,
        val cancelled: Boolean = false
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
        plan.createOperations.filter { it.existed }.map { it.relativePath }

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
                                            if (create != null) {
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
                                            errors.add("${create?.relativePath ?: delete?.relativePath}: ${e.message}")
                                        }
                                        cursor++
                                        if (System.nanoTime() - start >= 8_000_000) break
                                    }
                                }
                        }
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
            cancelled = cancelled
        )
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
        if (document != null && '\r' !in content && file.detectedLineSeparator?.contains('\r') != true) {
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

    private fun writeRaw(file: VirtualFile, write: () -> Unit) {
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
