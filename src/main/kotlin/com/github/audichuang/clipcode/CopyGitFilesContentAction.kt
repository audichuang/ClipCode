package com.github.audichuang.clipcode

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.vcs.commit.CommitWorkflowUi
import com.intellij.vcs.commit.AbstractCommitWorkflowHandler
import com.intellij.vcsUtil.VcsUtil
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JTree
import javax.swing.tree.TreePath

class CopyGitFilesContentAction : AnAction() {
    private val logger = Logger.getInstance(CopyGitFilesContentAction::class.java)

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Get selected changes using a "Greedy/Shotgun" strategy to overcome IntelliJ API limitations.
     * It collects data from ALL available sources and merges them to ensure nothing is missed.
     *
     * Problem: In Commit Tool Window, DataKeys like SELECTED_CHANGES, CHANGES, and even
     * VIRTUAL_FILE_ARRAY may return incomplete results when multiple files are selected.
     *
     * Solution: Collect from all sources without early returns, then union them with a Set.
     */
    private fun getSelectedChanges(e: AnActionEvent): List<Change> {
        val project = e.project ?: return emptyList()
        val allChanges = mutableSetOf<Change>()

        // 策略 1: 收集所有可能的 Change DataKeys (不進行數量驗證，全部收集)
        e.getData(VcsDataKeys.SELECTED_CHANGES)?.let {
            logger.info("Source SELECTED_CHANGES: found ${it.size}")
            allChanges.addAll(it)
        }

        e.getData(VcsDataKeys.CHANGE_LEAD_SELECTION)?.let {
            logger.info("Source CHANGE_LEAD_SELECTION: found ${it.size}")
            allChanges.addAll(it)
        }

        e.getData(VcsDataKeys.CHANGES)?.let {
            logger.info("Source CHANGES: found ${it.size}")
            allChanges.addAll(it)
        }

        // 策略 2: 透過 VirtualFile 反查 Change (這是最強的補強)
        // 如果 DataKeys 的 Change 列表不完整，我們用選中的檔案去 ChangeListManager 查
        val selectedFiles = getSelectedFiles(e)
        if (selectedFiles.isNotEmpty()) {
            val changeListManager = ChangeListManager.getInstance(project)
            val changesFromFiles = selectedFiles.mapNotNull { changeListManager.getChange(it) }

            logger.info("Source VirtualFiles -> ChangeListManager: found ${changesFromFiles.size}")
            allChanges.addAll(changesFromFiles)
        }

        // 策略 3: 如果以上全部加起來還是空的，才嘗試 UI Fallback
        if (allChanges.isEmpty()) {
            val uiFromKey: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
                ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui

            val uiChanges = uiFromKey?.getIncludedChanges() ?: emptyList()
            if (uiChanges.isNotEmpty() && selectedFiles.isNotEmpty()) {
                val selectedFilePaths = selectedFiles.map { VcsUtil.getFilePath(it) }.toSet()
                val matchedChanges = uiChanges.filter { change ->
                    val changeFilePath = change.afterRevision?.file ?: change.beforeRevision?.file
                    changeFilePath != null && selectedFilePaths.contains(changeFilePath)
                }
                logger.info("Source UI Fallback: matched ${matchedChanges.size}")
                allChanges.addAll(matchedChanges)
            }
        }

        logger.info("Final merged changes count: ${allChanges.size}")
        return allChanges.toList()
    }

    /**
     * Get selected files as fallback for Git Staging Area.
     * When VcsDataKeys.CHANGES is not available, try CommonDataKeys.
     *
     * IMPORTANT: In Commit Tool Window, DataKeys may return incomplete selection.
     * This method also tries to extract selection directly from JTree component.
     */
    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> {
        // 1️⃣ 首先嘗試從 JTree/ChangesTree 直接取得選中的節點
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        if (component is JTree) {
            val paths: Array<TreePath>? = component.selectionPaths
            if (paths != null && paths.isNotEmpty()) {
                val files = mutableListOf<VirtualFile>()
                for (path in paths) {
                    val node = path.lastPathComponent
                    extractFilesFromNode(node, files)
                }
                if (files.isNotEmpty()) {
                    val distinctFiles = files.distinct()
                    logger.info("getSelectedFiles: Found ${distinctFiles.size} files from JTree selection (${paths.size} paths)")
                    return distinctFiles.toTypedArray()
                }
            }
        }

        // 2️⃣ 嘗試 ChangesTree 特定的方法 - 使用 VcsTreeModelData
        if (component is ChangesTree) {
            try {
                val selectedUserObjects = com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
                    .selected(component)
                    .userObjects()
                val files = selectedUserObjects.mapNotNull { obj ->
                    when (obj) {
                        is Change -> obj.virtualFile
                        is VirtualFile -> obj
                        else -> null
                    }
                }.distinct()
                if (files.isNotEmpty()) {
                    logger.info("getSelectedFiles: Found ${files.size} files from ChangesTree VcsTreeModelData")
                    return files.toTypedArray()
                }
            } catch (ex: Exception) {
                logger.warn("Failed to get selection from ChangesTree: ${ex.message}")
            }
        }

        // 3️⃣ Try VIRTUAL_FILE_ARRAY (common in tree views)
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.let {
            if (it.isNotEmpty()) {
                logger.info("getSelectedFiles: Found ${it.size} files from VIRTUAL_FILE_ARRAY")
                return it
            }
        }

        // 4️⃣ Try single file
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
            logger.info("getSelectedFiles: Found single file from VIRTUAL_FILE: ${it.path}")
            return arrayOf(it)
        }

        logger.info("getSelectedFiles: No files found")
        return emptyArray()
    }

    /**
     * Recursively extract VirtualFiles from tree nodes.
     */
    private fun extractFilesFromNode(node: Any?, files: MutableList<VirtualFile>) {
        when (node) {
            is ChangesBrowserNode<*> -> {
                val userObject = node.userObject
                when (userObject) {
                    is Change -> {
                        userObject.virtualFile?.let { files.add(it) }
                    }
                    is VirtualFile -> {
                        files.add(userObject)
                    }
                }
                // 如果是目錄節點，可能需要遍歷子節點
                // 但通常選中的檔案節點就足夠了
            }
        }
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