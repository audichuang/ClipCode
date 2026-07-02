package com.github.audichuang.clipcode

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

@IdeBoundCode
class CopyGitFilesContentAction : AnAction() {
    private val logger = Logger.getInstance(CopyGitFilesContentAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    @IdeBoundCode
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

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Copying Git file content…", true) {
                private var resolvedEntries: List<GitContentResolver.ResolvedGitEntry> = emptyList()

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    resolvedEntries = GitContentResolver(logger).resolve(project, selection)
                    indicator.checkCanceled()
                }

                override fun onSuccess() {
                    if (project.isDisposed) return
                    handleResolvedEntries(e, project, pathResolver, resolvedEntries)
                }
            }
        )
    }

    @IdeBoundCode
    private fun handleResolvedEntries(
        e: AnActionEvent,
        project: Project,
        pathResolver: ClipboardPathResolver,
        resolvedEntries: List<GitContentResolver.ResolvedGitEntry>
    ) {
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

    @IdeBoundCode
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
        project: Project,
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedMarkerEntries: List<GitContentResolver.ResolvedGitEntry>,
        pathResolver: ClipboardPathResolver,
        settings: CopyFileContentSettings?
    ) {
        // 讀取 VFS bytes 與組合字串都搬到 BGT，避免在 EDT 上同步 I/O
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Building Git clipboard payload…", true) {
            private var payload: GitClipboardPayloadBuilder.Payload? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                payload = GitClipboardPayloadBuilder.build(
                    contentEntries, deletedMarkerEntries, pathResolver, settings, indicator
                )
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val result = payload ?: return
                copyToClipboard(result.text)
                if (settings?.state?.showCopyNotification == true) {
                    CopyFileContentAction.showNotification(
                        "<html><b>${result.summary}</b></html>",
                        NotificationType.INFORMATION,
                        project
                    )
                }
            }
        })
    }

    @IdeBoundCode
    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(text), null)
    }

    @IdeBoundCode
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null
    }
}
