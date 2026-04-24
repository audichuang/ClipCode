package com.github.audichuang.clipcode

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyGitFilesContentAction : AnAction() {
    private val logger = Logger.getInstance(CopyGitFilesContentAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            CopyFileContentAction.showNotification(
                "No project found. Action cannot proceed.",
                NotificationType.ERROR,
                null
            )
            return
        }

        val selection = GitSelectionCollector(logger).collect(e)
        val pathResolver = ClipboardPathResolver.fromProject(project)

        if (!selection.hasGitMetadata) {
            copySelectedFilesWithoutGitMetadata(e, selection.selectedFiles, pathResolver)
            return
        }

        val resolvedEntries = GitContentResolver(logger).resolve(project, selection)
        if (resolvedEntries.isEmpty()) {
            CopyFileContentAction.showNotification(
                "No files found in Git selection.",
                NotificationType.WARNING,
                project
            )
            return
        }

        val unresolvedEntries = resolvedEntries.filter {
            it.changeType != ChangeTypeLabel.DELETED && !it.hasContent
        }
        if (unresolvedEntries.isNotEmpty()) {
            val skippedPaths = unresolvedEntries.map { it.filePath.substringAfterLast('/') }
            CopyFileContentAction.showNotification(
                "<html><b>${unresolvedEntries.size} files could not be resolved:</b><br>${skippedPaths.joinToString(", ")}</html>",
                NotificationType.WARNING,
                project
            )
        }

        val contentEntries = resolvedEntries.filter { it.hasContent }
        val deletedMarkerEntries = resolvedEntries.filter {
            it.changeType == ChangeTypeLabel.DELETED && !it.hasContent
        }

        if (contentEntries.isEmpty() && deletedMarkerEntries.isEmpty()) {
            CopyFileContentAction.showNotification(
                "No accessible files found in Git selection.",
                NotificationType.WARNING,
                project
            )
            return
        }

        val settings = CopyFileContentSettings.getInstance(project)
        val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"

        if (contentEntries.isNotEmpty() &&
            contentEntries.all { it.hasVirtualFileContent } &&
            deletedMarkerEntries.isEmpty()
        ) {
            val indexedEntries = contentEntries.associateBy { it.virtualFile!!.path }
            CopyFileContentAction().performCopyFilesContent(
                e,
                contentEntries.map { it.virtualFile!! }.toTypedArray()
            ) { file, _ ->
                val entry = indexedEntries[file.path]
                GitClipboardFormatter.buildHeader(
                    headerFormat = headerFormat,
                    clipboardPath = pathResolver.toClipboardPath(entry?.filePath ?: file.path),
                    changeType = entry?.changeType
                )
            }
            return
        }

        copyResolvedEntries(
            project = project,
            contentEntries = contentEntries,
            deletedMarkerEntries = deletedMarkerEntries,
            pathResolver = pathResolver,
            settings = settings
        )
    }

    private fun copySelectedFilesWithoutGitMetadata(
        e: AnActionEvent,
        selectedFiles: List<VirtualFile>,
        pathResolver: ClipboardPathResolver
    ) {
        val project = e.project ?: return
        if (selectedFiles.isEmpty()) {
            CopyFileContentAction.showNotification(
                "No files selected in Git commit/staging view.",
                NotificationType.WARNING,
                project
            )
            return
        }

        val settings = CopyFileContentSettings.getInstance(project)
        val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
        CopyFileContentAction().performCopyFilesContent(
            e,
            selectedFiles.toTypedArray()
        ) { file, _ ->
            GitClipboardFormatter.buildHeader(
                headerFormat = headerFormat,
                clipboardPath = pathResolver.toClipboardPath(file.path),
                changeType = null
            )
        }
    }

    private fun copyResolvedEntries(
        project: com.intellij.openapi.project.Project,
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedMarkerEntries: List<GitContentResolver.ResolvedGitEntry>,
        pathResolver: ClipboardPathResolver,
        settings: CopyFileContentSettings?
    ) {
        val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
        val maxFileSizeBytes = settings?.state?.maxFileSizeKB?.times(1024L) ?: (500L * 1024L)
        val addExtraLine = settings?.state?.addExtraLineBetweenFiles == true
        val fileContents = mutableListOf<String>()
        var skippedSizeCount = 0

        if (!settings?.state?.preText.isNullOrEmpty()) {
            fileContents.add(settings!!.state.preText)
        }

        contentEntries.forEach { entry ->
            fileContents.add(
                GitClipboardFormatter.buildHeader(
                    headerFormat = headerFormat,
                    clipboardPath = pathResolver.toClipboardPath(entry.filePath),
                    changeType = entry.changeType
                )
            )
            skippedSizeCount += appendContent(fileContents, entry, maxFileSizeBytes)
            if (addExtraLine) {
                fileContents.add("")
            }
        }

        deletedMarkerEntries.forEach { entry ->
            fileContents.add(
                GitClipboardFormatter.buildHeader(
                    headerFormat = headerFormat,
                    clipboardPath = pathResolver.toClipboardPath(entry.filePath),
                    changeType = entry.changeType
                )
            )
            fileContents.add("// This file has been deleted in this change")
            if (addExtraLine) {
                fileContents.add("")
            }
        }

        if (!settings?.state?.postText.isNullOrEmpty()) {
            fileContents.add(settings!!.state.postText)
        }

        copyToClipboard(fileContents.joinToString("\n"))

        if (settings?.state?.showCopyNotification == true) {
            val filesFromDisk = contentEntries.count { it.hasVirtualFileContent } - skippedSizeCount.coerceAtMost(contentEntries.count { it.hasVirtualFileContent })
            val filesFromHistory = contentEntries.count { !it.hasVirtualFileContent && it.contentFromRevision != null }
            val copiedWithContent = contentEntries.size - skippedSizeCount
            val totalCopied = copiedWithContent + deletedMarkerEntries.size
            val skippedSuffix = if (skippedSizeCount > 0) " ($skippedSizeCount skipped: size exceeded)" else ""

            val message = when {
                contentEntries.isEmpty() && deletedMarkerEntries.size == 1 ->
                    "1 deleted file marker copied."
                contentEntries.isEmpty() ->
                    "${deletedMarkerEntries.size} deleted file markers copied."
                deletedMarkerEntries.isEmpty() && copiedWithContent == 1 && filesFromHistory == 1 ->
                    "1 file copied (from Git history)$skippedSuffix."
                deletedMarkerEntries.isEmpty() && filesFromHistory > 0 ->
                    "$copiedWithContent files copied ($filesFromDisk from disk, $filesFromHistory from Git history)$skippedSuffix."
                deletedMarkerEntries.isEmpty() ->
                    "$copiedWithContent files copied$skippedSuffix."
                filesFromHistory > 0 ->
                    "$totalCopied files copied ($filesFromDisk from disk, $filesFromHistory from Git history, ${deletedMarkerEntries.size} deleted)$skippedSuffix."
                else ->
                    "$totalCopied files copied ($copiedWithContent with content, ${deletedMarkerEntries.size} deleted)$skippedSuffix."
            }

            CopyFileContentAction.showNotification(
                "<html><b>$message</b></html>",
                NotificationType.INFORMATION,
                project
            )
        }
    }

    private fun appendContent(
        fileContents: MutableList<String>,
        entry: GitContentResolver.ResolvedGitEntry,
        maxFileSizeBytes: Long
    ): Int {
        val virtualFile = entry.virtualFile
        if (virtualFile != null && entry.hasVirtualFileContent) {
            if (virtualFile.length > maxFileSizeBytes) {
                logger.info("Skipping oversized Git file: ${entry.filePath}")
                fileContents.add("// File skipped: size exceeds limit (${virtualFile.length} bytes)")
                return 1
            }

            return try {
                fileContents.add(String(virtualFile.contentsToByteArray(), Charsets.UTF_8))
                0
            } catch (e: Exception) {
                logger.warn("Failed to read Git file content: ${entry.filePath}", e)
                fileContents.add("// Error reading file content")
                0
            }
        }

        val revisionContent = entry.contentFromRevision
        if (revisionContent != null) {
            val contentSizeBytes = revisionContent.toByteArray(Charsets.UTF_8).size.toLong()
            if (contentSizeBytes > maxFileSizeBytes) {
                logger.info("Skipping oversized Git revision content: ${entry.filePath}")
                fileContents.add("// File skipped: size exceeds limit ($contentSizeBytes bytes)")
                return 1
            }

            fileContents.add(revisionContent)
            return 0
        }

        fileContents.add("// Unable to read file content")
        return 0
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null
    }
}
