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
import com.intellij.openapi.vcs.VcsException
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
     * Additionally, UNTRACKED files appear as GitFileStatusNode, not Change objects.
     *
     * Solution: Collect from all sources without early returns, then union them with a Set.
     * For UNTRACKED files (GitFileStatusNode), create synthetic Change-like entries.
     * Priority: VcsTreeModelData > DataKeys > ChangeListManager > UI Fallback
     */
    private fun getSelectedChanges(e: AnActionEvent): List<Change> {
        val project = e.project ?: return emptyList()
        // 🔧 使用 Map 以檔案路徑為 key 進行去重（避免同一檔案從不同來源被重複加入）
        val allChangesMap = mutableMapOf<String, Change>()

        // Helper function to add change by path (deduplication)
        fun addChange(change: Change) {
            val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path
            if (path != null && !allChangesMap.containsKey(path)) {
                allChangesMap[path] = change
            }
        }

        // 🔧 策略 0 (最優先): 直接從 ChangesTree 使用 VcsTreeModelData
        // 這是最可靠的方式，因為它直接讀取 tree model 而非依賴 DataProvider
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        logger.warn("DEBUG getSelectedChanges: component = ${component?.javaClass?.name}")

        // 收集 UNTRACKED 檔案的路徑（這些檔案不會有對應的 Change 物件）
        val untrackedFilePaths = mutableSetOf<String>()

        if (component is ChangesTree) {
            try {
                val selectedUserObjects = com.intellij.openapi.vcs.changes.ui.VcsTreeModelData
                    .selected(component)
                    .userObjects()
                logger.warn("DEBUG VcsTreeModelData.selected().userObjects() size = ${selectedUserObjects.size}")

                for ((index, obj) in selectedUserObjects.withIndex()) {
                    logger.warn("DEBUG   userObject[$index]: ${obj?.javaClass?.name} = $obj")

                    when (obj) {
                        is Change -> {
                            addChange(obj)
                        }
                        else -> {
                            // 處理 GitFileStatusNode 等其他類型
                            // 使用反射取得 path，因為 GitFileStatusNode 是內部類別
                            try {
                                val pathMethod = obj?.javaClass?.getMethod("getFilePath")
                                val filePath = pathMethod?.invoke(obj)
                                if (filePath != null) {
                                    val pathStr = filePath.toString()
                                    logger.warn("DEBUG   -> Extracted filePath: $pathStr")
                                    untrackedFilePaths.add(pathStr)
                                }
                            } catch (ex: Exception) {
                                // 嘗試其他方式取得路徑
                                val objStr = obj.toString()
                                val pathMatch = Regex("path=([^,)]+)").find(objStr)
                                if (pathMatch != null) {
                                    val pathStr = pathMatch.groupValues[1]
                                    logger.warn("DEBUG   -> Extracted path from toString: $pathStr")
                                    untrackedFilePaths.add(pathStr)
                                }
                            }
                        }
                    }
                }

                if (allChangesMap.isNotEmpty()) {
                    logger.warn("DEBUG Source VcsTreeModelData (ChangesTree): found ${allChangesMap.size} Changes")
                }
                if (untrackedFilePaths.isNotEmpty()) {
                    logger.warn("DEBUG Source VcsTreeModelData: found ${untrackedFilePaths.size} untracked file paths")
                }
            } catch (ex: Exception) {
                logger.warn("Failed to get selection from VcsTreeModelData: ${ex.message}")
            }
        }

        // 策略 1: 收集所有可能的 Change DataKeys (不進行數量驗證，全部收集)
        e.getData(VcsDataKeys.SELECTED_CHANGES)?.let {
            logger.warn("DEBUG Source SELECTED_CHANGES: found ${it.size}")
            it.forEach { change -> addChange(change) }
        }

        e.getData(VcsDataKeys.CHANGE_LEAD_SELECTION)?.let {
            logger.warn("DEBUG Source CHANGE_LEAD_SELECTION: found ${it.size}")
            it.forEach { change -> addChange(change) }
        }

        e.getData(VcsDataKeys.CHANGES)?.let {
            logger.warn("DEBUG Source CHANGES: found ${it.size}")
            it.forEach { change -> addChange(change) }
        }

        // 策略 2: 透過 VirtualFile 反查 Change (這是最強的補強)
        // 如果 DataKeys 的 Change 列表不完整，我們用選中的檔案去 ChangeListManager 查
        val selectedFiles = getSelectedFiles(e)
        logger.warn("DEBUG getSelectedFiles returned: ${selectedFiles.size} files")
        if (selectedFiles.isNotEmpty()) {
            val changeListManager = ChangeListManager.getInstance(project)
            val changesFromFiles = selectedFiles.mapNotNull { changeListManager.getChange(it) }

            logger.warn("DEBUG Source VirtualFiles -> ChangeListManager: found ${changesFromFiles.size}")
            changesFromFiles.forEach { change -> addChange(change) }

            // 🔧 對於 UNTRACKED 檔案，ChangeListManager.getChange() 會回傳 null
            // 我們需要把這些檔案加到 untrackedFilePaths
            for (file in selectedFiles) {
                if (changeListManager.getChange(file) == null) {
                    untrackedFilePaths.add(file.path)
                    logger.warn("DEBUG   File not in ChangeListManager (likely UNTRACKED): ${file.path}")
                }
            }
        }

        // 策略 3: 如果以上全部加起來還是空的，才嘗試 UI Fallback
        if (allChangesMap.isEmpty() && untrackedFilePaths.isEmpty()) {
            val uiFromKey: CommitWorkflowUi? = e.getData(VcsDataKeys.COMMIT_WORKFLOW_UI)
                ?: (e.getData(VcsDataKeys.COMMIT_WORKFLOW_HANDLER) as? AbstractCommitWorkflowHandler<*, *>)?.ui

            val uiChanges = uiFromKey?.getIncludedChanges() ?: emptyList()
            if (uiChanges.isNotEmpty() && selectedFiles.isNotEmpty()) {
                val selectedFilePaths = selectedFiles.map { VcsUtil.getFilePath(it) }.toSet()
                val matchedChanges = uiChanges.filter { change ->
                    val changeFilePath = change.afterRevision?.file ?: change.beforeRevision?.file
                    changeFilePath != null && selectedFilePaths.contains(changeFilePath)
                }
                logger.warn("DEBUG Source UI Fallback: matched ${matchedChanges.size}")
                matchedChanges.forEach { change -> addChange(change) }
            }
        }

        logger.warn("DEBUG Final merged changes count: ${allChangesMap.size}, untracked paths: ${untrackedFilePaths.size}")

        // 儲存 untracked 檔案路徑供 actionPerformed 使用
        this.pendingUntrackedPaths = untrackedFilePaths

        return allChangesMap.values.toList()
    }

    // 暫存 UNTRACKED 檔案路徑
    private var pendingUntrackedPaths: Set<String> = emptySet()

    /**
     * Get selected files as fallback for Git Staging Area.
     * When VcsDataKeys.CHANGES is not available, try CommonDataKeys.
     *
     * IMPORTANT: In Commit Tool Window, DataKeys may return incomplete selection.
     * This method prioritizes VcsTreeModelData which is the most reliable source.
     */
    private fun getSelectedFiles(e: AnActionEvent): Array<VirtualFile> {
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)

        // 1️⃣ 最優先：使用 VcsTreeModelData（最可靠，直接讀取 tree model）
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
                    logger.info("getSelectedFiles: Found ${files.size} files from VcsTreeModelData (most reliable)")
                    return files.toTypedArray()
                }
            } catch (ex: Exception) {
                logger.warn("Failed to get selection from VcsTreeModelData: ${ex.message}")
            }
        }

        // 2️⃣ 備援：從 JTree 直接取得選中的節點
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

        // 🔍 DEBUG: 檢查 component 類型
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        logger.warn("DEBUG actionPerformed: component type = ${component?.javaClass?.name}")

        // Get selected changes from Git Staging Area, Commit UI, or Changes view
        val selectedChanges = getSelectedChanges(e)
        logger.warn("DEBUG actionPerformed: selectedChanges.size = ${selectedChanges.size}")
        selectedChanges.forEachIndexed { index, change ->
            logger.warn("DEBUG   Change[$index]: ${change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path}")
        }

        // If no changes found, try fallback to selected files (for Git Staging Area)
        if (selectedChanges.isEmpty()) {
            val selectedFiles = getSelectedFiles(e)
            logger.warn("DEBUG actionPerformed: Fallback - selectedFiles.size = ${selectedFiles.size}")

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

            // 用於儲存從 Git 歷史讀取的內容（當 virtualFile 無法解析時）
            var contentFromRevision: String? = null

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

                // 🔧 嘗試 4: 從 Git ContentRevision 讀取內容（適用於 Git Log 歷史版本）
                if (virtualFile == null) {
                    try {
                        contentFromRevision = change.afterRevision?.content
                            ?: change.beforeRevision?.content
                        if (contentFromRevision != null) {
                            logger.info("Resolved content from ContentRevision (Git history): $filePath")
                        }
                    } catch (ex: Exception) {
                        // 捕捉所有異常（VcsException, IOException, RuntimeException 等）
                        logger.warn("Failed to get content from ContentRevision: ${ex.message}")
                    }
                }
            }

            changeInfoList.add(ChangeInfo(
                change = change,
                changeType = changeType,
                filePath = filePath,
                virtualFile = virtualFile,
                contentFromRevision = contentFromRevision
            ))
        }

        // 🔧 處理 UNTRACKED 檔案（這些檔案沒有對應的 Change 物件）
        val untrackedPaths = pendingUntrackedPaths
        logger.warn("DEBUG Processing ${untrackedPaths.size} untracked file paths")
        for (untrackedPath in untrackedPaths) {
            // 檢查是否已經在 changeInfoList 中
            if (changeInfoList.any { it.filePath == untrackedPath }) {
                logger.warn("DEBUG   Skipping duplicate untracked path: $untrackedPath")
                continue
            }

            var virtualFile = LocalFileSystem.getInstance().findFileByPath(untrackedPath)
            if (virtualFile == null) {
                virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(untrackedPath)
            }

            if (virtualFile != null) {
                logger.warn("DEBUG   Added untracked file: $untrackedPath")
                changeInfoList.add(ChangeInfo(
                    change = null,  // UNTRACKED 檔案沒有 Change 物件
                    changeType = "[NEW]",  // 標記為新檔案
                    filePath = untrackedPath,
                    virtualFile = virtualFile
                ))
            } else {
                logger.warn("DEBUG   Could not resolve untracked file: $untrackedPath")
            }
        }
        // 清空暫存
        pendingUntrackedPaths = emptySet()

        if (changeInfoList.isEmpty()) {
            CopyFileContentAction.showNotification("No files found in selection.", NotificationType.WARNING, project)
            return
        }

        // Separate deleted files from accessible files
        val deletedFiles = changeInfoList.filter { it.change?.type == Change.Type.DELETED }
        val accessibleFiles = changeInfoList.filter {
            (it.virtualFile != null && it.virtualFile.isValid && it.virtualFile.exists())
            || it.contentFromRevision != null  // 🔧 包含從 Git 歷史讀取內容的檔案
        }

        // Log files that couldn't be accessed and warn user
        val skippedFiles = changeInfoList.filter {
            it.change?.type != Change.Type.DELETED &&
            (it.virtualFile == null || !it.virtualFile.isValid || !it.virtualFile.exists()) &&
            it.contentFromRevision == null  // 🔧 只有當也沒有 contentFromRevision 時才算 skipped
        }
        if (skippedFiles.isNotEmpty()) {
            val skippedPaths = skippedFiles.map { it.filePath.substringAfterLast('/') }
            logger.warn("Skipped ${skippedFiles.size} files due to missing virtualFile: ${skippedFiles.map { it.filePath }}")
            CopyFileContentAction.showNotification(
                "<html><b>${skippedFiles.size} files could not be resolved:</b><br>${skippedPaths.joinToString(", ")}</html>",
                NotificationType.WARNING,
                project
            )
        }

        val settings = CopyFileContentSettings.getInstance(project)

        // 🔧 分離出只有 contentFromRevision 的檔案（這些無法使用 performCopyFilesContent）
        val filesWithVirtualFile = accessibleFiles.filter {
            it.virtualFile != null && it.virtualFile.isValid && it.virtualFile.exists()
        }
        val filesWithOnlyRevisionContent = accessibleFiles.filter {
            it.contentFromRevision != null &&
            (it.virtualFile == null || !it.virtualFile.isValid || !it.virtualFile.exists())
        }

        // Case 1: Only files with virtualFile (no deleted, no revision-only) - use performCopyFilesContent
        if (filesWithVirtualFile.isNotEmpty() && deletedFiles.isEmpty() && filesWithOnlyRevisionContent.isEmpty()) {
            val copyFileContentAction = CopyFileContentAction()
            val virtualFiles = filesWithVirtualFile.mapNotNull { it.virtualFile }.toTypedArray()

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
            val relativePath = getRelativePath(project, accessibleInfo.filePath)
            val headerFormat = settings?.state?.headerFormat ?: "// file: \$FILE_PATH"
            val header = if (accessibleInfo.changeType.isNotEmpty()) {
                headerFormat.replace("\$FILE_PATH", "${accessibleInfo.changeType} $relativePath")
            } else {
                headerFormat.replace("\$FILE_PATH", relativePath)
            }

            fileContents.add(header)

            // ✅ 修復 OOM 風險：先檢查檔案大小
            val maxFileSizeBytes = settings?.state?.maxFileSizeKB?.times(1024L) ?: (500L * 1024L)

            // 🔧 優先使用 virtualFile，若無則使用 contentFromRevision（Git Log 歷史版本）
            val file = accessibleInfo.virtualFile
            if (file != null && file.isValid && file.exists()) {
                // 從 virtualFile 讀取（標準路徑）
                if (file.length > maxFileSizeBytes) {
                    logger.info("Skipping file in Git changes: ${accessibleInfo.filePath} - File size (${file.length} bytes) exceeds limit")
                    fileContents.add("// File skipped: size exceeds limit (${file.length} bytes)")
                } else {
                    try {
                        val content = String(file.contentsToByteArray(), Charsets.UTF_8)
                        fileContents.add(content)
                    } catch (ex: Exception) {
                        logger.warn("Failed to read file content: ${accessibleInfo.filePath}", ex)
                        fileContents.add("// Error reading file content")
                    }
                }
            } else if (accessibleInfo.contentFromRevision != null) {
                // 🔧 從 Git ContentRevision 讀取（Git Log 歷史版本）
                val content = accessibleInfo.contentFromRevision
                // 使用 UTF-8 byte 大小檢查（而非字元數），因為 maxFileSizeBytes 是 bytes
                val contentSizeBytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                if (contentSizeBytes > maxFileSizeBytes) {
                    logger.info("Skipping file from Git history: ${accessibleInfo.filePath} - Content size ($contentSizeBytes bytes) exceeds limit")
                    fileContents.add("// File skipped: size exceeds limit ($contentSizeBytes bytes)")
                } else {
                    fileContents.add(content)
                }
            } else {
                // 無法讀取內容
                logger.warn("No content available for: ${accessibleInfo.filePath}")
                fileContents.add("// Unable to read file content")
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
            val fromDiskCount = filesWithVirtualFile.size
            val fromGitHistoryCount = filesWithOnlyRevisionContent.size
            val totalAccessible = accessibleFiles.size
            val totalFiles = totalAccessible + deletedFiles.size

            val message = when {
                accessibleFiles.isEmpty() && deletedFiles.size == 1 ->
                    "1 deleted file marker copied."
                accessibleFiles.isEmpty() ->
                    "${deletedFiles.size} deleted file markers copied."
                deletedFiles.isEmpty() && totalAccessible == 1 && fromGitHistoryCount == 1 ->
                    "1 file copied (from Git history)."
                deletedFiles.isEmpty() && totalAccessible == 1 ->
                    "1 file copied."
                deletedFiles.isEmpty() && fromGitHistoryCount > 0 ->
                    "$totalAccessible files copied ($fromDiskCount from disk, $fromGitHistoryCount from Git history)."
                deletedFiles.isEmpty() ->
                    "$totalAccessible files copied."
                fromGitHistoryCount > 0 ->
                    "$totalFiles files copied ($fromDiskCount from disk, $fromGitHistoryCount from Git history, ${deletedFiles.size} deleted)."
                else ->
                    "$totalFiles files copied ($totalAccessible with content, ${deletedFiles.size} deleted)."
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
        val change: Change?,  // 可為 null（UNTRACKED 檔案沒有 Change 物件）
        val changeType: String,
        val filePath: String,
        val virtualFile: VirtualFile?,
        val contentFromRevision: String? = null  // 從 Git 歷史讀取的內容（適用於 Git Log）
    )
}