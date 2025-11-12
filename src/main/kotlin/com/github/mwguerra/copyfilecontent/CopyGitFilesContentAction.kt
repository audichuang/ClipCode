package com.github.mwguerra.copyfilecontent

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyGitFilesContentAction : AnAction() {
    private val logger = Logger.getInstance(CopyGitFilesContentAction::class.java)

    /**
     * Get selected changes from Git Staging Area, Commit UI, or traditional Changes view.
     * Prioritizes CommitWorkflowUi.getIncludedChanges() for Git Staging / Modal / Non-Modal support.
     * Falls back to VcsDataKeys.CHANGES for backward compatibility.
     */
    private fun getSelectedChanges(e: AnActionEvent): List<Change> {
        // Try CommitWorkflowUi first (supports Git Staging / Modal / Non-Modal)
        val uiFromKey: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
            ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui

        val included = uiFromKey?.getIncludedChanges()
        if (included != null && included.isNotEmpty()) {
            return included
        }

        // Fallback to traditional Changes data key for older UI compatibility
        return e.getData(VcsDataKeys.CHANGES)?.toList().orEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            CopyFileContentAction.showNotification("No project found. Action cannot proceed.", NotificationType.ERROR, null)
            return
        }

        // Get selected changes from Git Staging Area, Commit UI, or Changes view
        val selectedChanges = getSelectedChanges(e)
        if (selectedChanges.isEmpty()) {
            CopyFileContentAction.showNotification(
                "No files selected in Git commit/staging view.",
                NotificationType.ERROR,
                project
            )
            return
        }

        // Process changes with type information
        val changeInfoList = mutableListOf<ChangeInfo>()

        for (change in selectedChanges) {
            val changeType = getChangeTypeLabel(change)
            val filePath = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path

            if (filePath == null) {
                logger.info("Skipping change: Unable to determine file path")
                continue
            }

            val virtualFile = when {
                // For new files or modifications, use the after revision file
                change.afterRevision != null -> change.afterRevision?.file?.virtualFile
                // For deleted files, use the before revision file (may not exist)
                change.beforeRevision != null -> change.beforeRevision?.file?.virtualFile
                else -> null
            }

            changeInfoList.add(ChangeInfo(
                change = change,
                changeType = changeType,
                filePath = filePath,
                virtualFile = virtualFile
            ))
        }

        if (changeInfoList.isEmpty()) {
            CopyFileContentAction.showNotification("No files found in selection.", NotificationType.WARNING, project)
            return
        }

        // Separate deleted files from accessible files
        val deletedFiles = changeInfoList.filter { it.change.type == Change.Type.DELETED }
        val accessibleFiles = changeInfoList.filter { it.virtualFile != null && it.virtualFile.exists() }
        val settings = CopyFileContentSettings.getInstance(project)

        // Process accessible files using CopyFileContentAction with custom headers
        if (accessibleFiles.isNotEmpty()) {
            val copyFileContentAction = CopyFileContentAction()
            val virtualFiles = accessibleFiles.mapNotNull { it.virtualFile }.toTypedArray()

            // Create custom header generator that includes change type
            val customHeaderGenerator: (VirtualFile, String) -> String = { file, relativePath ->
                val changeInfo = accessibleFiles.find { it.virtualFile == file }
                val changeTypeLabel = changeInfo?.changeType ?: ""
                val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"

                if (changeTypeLabel.isNotEmpty()) {
                    headerFormat.replace("\$FILE_PATH", "$changeTypeLabel $relativePath")
                } else {
                    headerFormat.replace("\$FILE_PATH", relativePath)
                }
            }

            copyFileContentAction.performCopyFilesContent(e, virtualFiles, customHeaderGenerator)
        }

        // Handle deleted files separately - create markers and copy to clipboard
        if (deletedFiles.isNotEmpty()) {
            val fileContents = mutableListOf<String>()

            if (settings != null && settings.state.preText.isNotEmpty()) {
                fileContents.add(settings.state.preText)
            }

            for (deletedInfo in deletedFiles) {
                val relativePath = getRelativePath(project, deletedInfo.filePath)
                val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
                val header = headerFormat.replace("\$FILE_PATH", "${deletedInfo.changeType} $relativePath")

                fileContents.add(header)
                fileContents.add("// This file has been deleted in this change")

                if (settings?.state?.addExtraLineBetweenFiles == true) {
                    fileContents.add("")
                }
            }

            if (settings != null && settings.state.postText.isNotEmpty()) {
                fileContents.add(settings.state.postText)
            }

            val clipboardText = fileContents.joinToString(separator = "\n")
            copyToClipboard(clipboardText)

            // Show notification for deleted files
            if (settings?.state?.showCopyNotification == true) {
                val totalFiles = accessibleFiles.size + deletedFiles.size
                val message = when {
                    accessibleFiles.isEmpty() && deletedFiles.size == 1 ->
                        "1 deleted file marker copied."
                    accessibleFiles.isEmpty() ->
                        "${deletedFiles.size} deleted file markers copied."
                    else ->
                        "$totalFiles files copied (${accessibleFiles.size} with content, ${deletedFiles.size} deleted)."
                }
                CopyFileContentAction.showNotification("<html><b>$message</b></html>", NotificationType.INFORMATION, project)
            }
        }
    }

    private fun getChangeTypeLabel(change: Change): String {
        return when (change.type) {
            Change.Type.NEW -> "[NEW]"
            Change.Type.DELETED -> "[DELETED]"
            Change.Type.MODIFICATION -> "[MODIFIED]"
            Change.Type.MOVED -> "[MOVED]"
            else -> ""
        }
    }

    private fun getRelativePath(project: Project, absolutePath: String): String {
        val projectRoot = ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
        return if (projectRoot != null) {
            val projectPath = projectRoot.path
            if (absolutePath.startsWith(projectPath)) {
                absolutePath.substring(projectPath.length).trimStart('/')
            } else {
                absolutePath
            }
        } else {
            absolutePath
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val data = StringSelection(text)
        clipboard.setContents(data, null)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val hasChanges = getSelectedChanges(e).isNotEmpty()

        // Enable action only when there are selected changes and project is available
        e.presentation.isEnabled = project != null && hasChanges
        e.presentation.isVisible = project != null
    }

    private data class ChangeInfo(
        val change: Change,
        val changeType: String,
        val filePath: String,
        val virtualFile: VirtualFile?
    )
}