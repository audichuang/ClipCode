package com.github.mwguerra.copyfilecontent

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
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

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Get selected changes from Git Staging Area, Commit UI, or traditional Changes view.
     * Prioritizes VcsDataKeys.CHANGES (user's right-click selection) over getIncludedChanges().
     * For Git Staging Area, matches selected files against available changes.
     */
    private fun getSelectedChanges(e: AnActionEvent): List<Change> {
        // 方法 1: VcsDataKeys.CHANGES（傳統 Changes view 有效）
        val selectedChanges = e.getData(VcsDataKeys.CHANGES)?.toList()
        if (selectedChanges != null && selectedChanges.isNotEmpty()) {
            logger.info("getSelectedChanges: Found ${selectedChanges.size} from VcsDataKeys.CHANGES")
            return selectedChanges
        }

        // 方法 2: 透過選中的檔案匹配 changes（Git Staging Area）
        val selectedFiles = getSelectedFiles(e)
        if (selectedFiles.isEmpty()) {
            logger.info("getSelectedChanges: No selected files found")
            return emptyList()
        }

        // 獲取所有可用 changes
        val uiFromKey: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
            ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui

        val allChanges = uiFromKey?.getIncludedChanges() ?: emptyList()
        if (allChanges.isEmpty()) {
            logger.info("getSelectedChanges: No changes available from CommitWorkflowUi")
            return emptyList()
        }

        // 用檔案路徑匹配：只返回選中檔案對應的 changes
        val selectedPaths = selectedFiles.map { it.path }.toSet()
        val matchedChanges = allChanges.filter { change ->
            val changePath = change.afterRevision?.file?.path
                ?: change.beforeRevision?.file?.path
            changePath != null && selectedPaths.contains(changePath)
        }

        logger.info("getSelectedChanges: Matched ${matchedChanges.size} changes from ${selectedFiles.size} selected files")
        return matchedChanges
    }

    /**
     * Get selected files as fallback for Git Staging Area.
     * When VcsDataKeys.CHANGES is not available, try CommonDataKeys.
     */
    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> {
        // Try VIRTUAL_FILE_ARRAY first (common in tree views)
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.let {
            if (it.isNotEmpty()) {
                logger.info("getSelectedFiles: Found ${it.size} files from VIRTUAL_FILE_ARRAY")
                return it
            }
        }

        // Try single file
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
            logger.info("getSelectedFiles: Found single file from VIRTUAL_FILE: ${it.path}")
            return arrayOf(it)
        }

        logger.info("getSelectedFiles: No files found")
        return emptyArray()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            CopyFileContentAction.showNotification("No project found. Action cannot proceed.", NotificationType.ERROR, null)
            return
        }

        // Get selected changes from Git Staging Area, Commit UI, or Changes view
        val selectedChanges = getSelectedChanges(e)
        logger.info("actionPerformed: selectedChanges.size = ${selectedChanges.size}")

        // If no changes found, try fallback to selected files (for Git Staging Area)
        if (selectedChanges.isEmpty()) {
            val selectedFiles = getSelectedFiles(e)
            logger.info("actionPerformed: Fallback - selectedFiles.size = ${selectedFiles.size}")

            if (selectedFiles.isEmpty()) {
                CopyFileContentAction.showNotification(
                    "No files selected in Git commit/staging view.",
                    NotificationType.WARNING,
                    project
                )
                return
            }

            // Use CopyFileContentAction directly with selected files (without change type labels)
            val copyFileContentAction = CopyFileContentAction()
            copyFileContentAction.performCopyFilesContent(e, selectedFiles, null)
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
        e.presentation.isVisible = project != null
        e.presentation.isEnabled = project != null
    }

    private data class ChangeInfo(
        val change: Change,
        val changeType: String,
        val filePath: String,
        val virtualFile: VirtualFile?
    )
}