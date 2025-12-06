package com.github.audichuang.clipcode

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
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcsUtil.VcsUtil
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyGitFilesContentAction : AnAction() {
    private val logger = Logger.getInstance(CopyGitFilesContentAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Get selected changes from Git Staging Area, Commit UI, or traditional Changes view.
     * IntelliJ 2025 compatibility: Uses SELECTED_CHANGES first (recommended for 2025),
     * then falls back to CHANGE_LEAD_SELECTION and CHANGES for older versions.
     * For Git Staging Area, matches selected files against available changes using FilePath comparison.
     */
    private fun getSelectedChanges(e: AnActionEvent): List<Change> {
        // 1️⃣ 首選：SELECTED_CHANGES（IntelliJ 2025 推薦，在 Git Staging Area 中有效）
        e.getData(VcsDataKeys.SELECTED_CHANGES)?.let { selected ->
            if (selected.isNotEmpty()) {
                logger.info("getSelectedChanges: ${selected.size} from SELECTED_CHANGES")
                return selected.toList()
            }
        }

        // 2️⃣ 嘗試 CHANGE_LEAD_SELECTION（樹狀結構中明確選中的節點）
        e.getData(VcsDataKeys.CHANGE_LEAD_SELECTION)?.let { lead ->
            if (lead.isNotEmpty()) {
                logger.info("getSelectedChanges: ${lead.size} from CHANGE_LEAD_SELECTION")
                return lead.toList()
            }
        }

        // 3️⃣ Legacy：CHANGES（傳統 Changes view，IntelliJ 2024 及之前版本）
        e.getData(VcsDataKeys.CHANGES)?.let { changes ->
            if (changes.isNotEmpty()) {
                logger.info("getSelectedChanges: ${changes.size} from CHANGES")
                return changes.toList()
            }
        }

        // 4️⃣ Fallback：透過 CommitWorkflowUi + FilePath 物件比較
        val selectedFiles = getSelectedFiles(e)
        if (selectedFiles.isEmpty()) {
            logger.info("getSelectedChanges: No selected files found")
            return emptyList()
        }

        val uiFromKey: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
            ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui

        val allChanges = uiFromKey?.getIncludedChanges() ?: emptyList()
        if (allChanges.isEmpty()) {
            logger.info("getSelectedChanges: No changes available from CommitWorkflowUi")
            return emptyList()
        }

        // 使用 FilePath 物件比較（而非字串比較）- 解決路徑分隔符和大小寫問題
        val selectedFilePaths = selectedFiles.map { VcsUtil.getFilePath(it) }.toSet()
        val matchedChanges = allChanges.filter { change ->
            val changeFilePath = change.afterRevision?.file ?: change.beforeRevision?.file
            changeFilePath != null && selectedFilePaths.contains(changeFilePath)
        }

        logger.info("getSelectedChanges: Matched ${matchedChanges.size} from ${selectedFiles.size} files (FilePath comparison)")
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

            // Try to get virtualFile from change with multi-step fallback
            var virtualFile = change.afterRevision?.file?.virtualFile
                ?: change.beforeRevision?.file?.virtualFile

            // If virtualFile is null, try multiple resolution strategies (IntelliJ 2025 fix)
            if (virtualFile == null && change.type != Change.Type.DELETED) {
                // 嘗試 1: 直接查找（快速，不強制 VFS 刷新）
                virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath)

                // 嘗試 2: 正規化路徑後查找（解決 Windows 路徑分隔符問題）
                if (virtualFile == null) {
                    val normalizedPath = filePath.replace('\\', '/')
                    virtualFile = LocalFileSystem.getInstance().findFileByPath(normalizedPath)
                }

                // 嘗試 3: 強制刷新 VFS（最後手段，較慢但可靠）
                if (virtualFile == null) {
                    virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)
                }

                if (virtualFile != null) {
                    logger.info("Resolved virtualFile after fallback resolution: $filePath")
                }
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
        val accessibleFiles = changeInfoList.filter {
            it.virtualFile != null && it.virtualFile.isValid && it.virtualFile.exists()
        }

        // Log files that couldn't be accessed
        val skippedFiles = changeInfoList.filter {
            it.change.type != Change.Type.DELETED &&
            (it.virtualFile == null || !it.virtualFile.isValid || !it.virtualFile.exists())
        }
        if (skippedFiles.isNotEmpty()) {
            logger.warn("Skipped ${skippedFiles.size} files due to missing virtualFile: ${skippedFiles.map { it.filePath }}")
        }

        val settings = CopyFileContentSettings.getInstance(project)

        // Case 1: Only accessible files (no deleted files) - use performCopyFilesContent
        if (accessibleFiles.isNotEmpty() && deletedFiles.isEmpty()) {
            val copyFileContentAction = CopyFileContentAction()
            val virtualFiles = accessibleFiles.mapNotNull { it.virtualFile }.toTypedArray()

            // Create custom header generator that includes change type
            val customHeaderGenerator: (VirtualFile, String) -> String = { file, relativePath ->
                // Use path comparison instead of object identity (VFS refresh may change reference)
                val changeInfo = accessibleFiles.find { it.virtualFile?.path == file.path }
                val changeTypeLabel = changeInfo?.changeType ?: ""
                val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"

                if (changeTypeLabel.isNotEmpty()) {
                    headerFormat.replace("\$FILE_PATH", "$changeTypeLabel $relativePath")
                } else {
                    headerFormat.replace("\$FILE_PATH", relativePath)
                }
            }

            copyFileContentAction.performCopyFilesContent(e, virtualFiles, customHeaderGenerator)
            return
        }

        // Case 2 & 3: Only deleted files OR both accessible and deleted files
        // Build all content ourselves to avoid clipboard overwrite
        val fileContents = mutableListOf<String>()

        if (settings != null && settings.state.preText.isNotEmpty()) {
            fileContents.add(settings.state.preText)
        }

        // Process accessible files (when there are also deleted files)
        for (accessibleInfo in accessibleFiles) {
            val file = accessibleInfo.virtualFile ?: continue
            if (!file.isValid || !file.exists()) continue

            val relativePath = getRelativePath(project, accessibleInfo.filePath)
            val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
            val header = if (accessibleInfo.changeType.isNotEmpty()) {
                headerFormat.replace("\$FILE_PATH", "${accessibleInfo.changeType} $relativePath")
            } else {
                headerFormat.replace("\$FILE_PATH", relativePath)
            }

            fileContents.add(header)

            // Read file content
            try {
                val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                fileContents.add(content)
            } catch (ex: Exception) {
                logger.warn("Failed to read file content: ${accessibleInfo.filePath}", ex)
                fileContents.add("// Error reading file content")
            }

            if (settings?.state?.addExtraLineBetweenFiles == true) {
                fileContents.add("")
            }
        }

        // Process deleted files
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

        // Show notification
        if (settings?.state?.showCopyNotification == true) {
            val totalFiles = accessibleFiles.size + deletedFiles.size
            val message = when {
                accessibleFiles.isEmpty() && deletedFiles.size == 1 ->
                    "1 deleted file marker copied."
                accessibleFiles.isEmpty() ->
                    "${deletedFiles.size} deleted file markers copied."
                deletedFiles.isEmpty() && accessibleFiles.size == 1 ->
                    "1 file copied."
                deletedFiles.isEmpty() ->
                    "${accessibleFiles.size} files copied."
                else ->
                    "$totalFiles files copied (${accessibleFiles.size} with content, ${deletedFiles.size} deleted)."
            }
            CopyFileContentAction.showNotification("<html><b>$message</b></html>", NotificationType.INFORMATION, project)
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