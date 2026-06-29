package com.github.audichuang.clipcode

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyFileContentAction : AnAction() {
    private val logger = Logger.getInstance(CopyFileContentAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    @IdeBoundCode
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showNotification("No project found. Action cannot proceed.", NotificationType.ERROR, null)
            return
        }
        
        // 嘗試多種方式取得選中的檔案（特別是 External Libraries）
        val selectedFiles = getSelectedVirtualFiles(e)
        
        if (selectedFiles.isEmpty()) {
            showNotification("No files selected.", NotificationType.ERROR, project)
            return
        }

        performCopyFilesContent(e, selectedFiles)
    }
    
    /**
     * 從多種來源嘗試取得選中的 VirtualFiles。
     * External Libraries 裡的檔案通常透過 PSI_FILE 或 NAVIGATABLE 提供，而非 VIRTUAL_FILE_ARRAY。
     * 同時支援選取 Package（資料夾）節點，會遞歸取得所有子檔案。
     */
    @IdeBoundCode
    private fun getSelectedVirtualFiles(e: AnActionEvent): Array<VirtualFile> {
        val collectedFiles = mutableListOf<VirtualFile>()
        
        // 1. 首先嘗試標準的 VIRTUAL_FILE_ARRAY
        e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.let {
            if (it.isNotEmpty()) {
                logger.info("getSelectedVirtualFiles: Found ${it.size} items from VIRTUAL_FILE_ARRAY")
                for (file in it) {
                    if (file.isDirectory) {
                        // 遞歸收集資料夾中的所有檔案
                        collectFilesFromDirectory(file, collectedFiles)
                    } else {
                        collectedFiles.add(file)
                    }
                }
                if (collectedFiles.isNotEmpty()) {
                    return collectedFiles.distinct().toTypedArray()
                }
            }
        }
        
        // 2. 嘗試單一檔案/資料夾 VIRTUAL_FILE
        e.getData(CommonDataKeys.VIRTUAL_FILE)?.let {
            logger.info("getSelectedVirtualFiles: Found item from VIRTUAL_FILE: ${it.path} (isDirectory=${it.isDirectory})")
            if (it.isDirectory) {
                collectFilesFromDirectory(it, collectedFiles)
            } else {
                collectedFiles.add(it)
            }
            if (collectedFiles.isNotEmpty()) {
                return collectedFiles.distinct().toTypedArray()
            }
        }
        
        // 3. 嘗試從 PSI_FILE 取得（External Libraries 的單一檔案常用此方式）
        e.getData(CommonDataKeys.PSI_FILE)?.let { psiFile ->
            psiFile.virtualFile?.let { virtualFile ->
                logger.info("getSelectedVirtualFiles: Found file from PSI_FILE: ${virtualFile.path}")
                return arrayOf(virtualFile)
            }
        }
        
        // 4. 嘗試從 PSI_ELEMENT 取得（支援 PsiDirectory 和其他 PSI 元素）
        e.getData(CommonDataKeys.PSI_ELEMENT)?.let { psiElement ->
            when (psiElement) {
                is com.intellij.psi.PsiDirectory -> {
                    // 選取的是資料夾節點
                    logger.info("getSelectedVirtualFiles: Found PsiDirectory: ${psiElement.virtualFile.path}")
                    collectFilesFromDirectory(psiElement.virtualFile, collectedFiles)
                    if (collectedFiles.isNotEmpty()) {
                        return collectedFiles.distinct().toTypedArray()
                    }
                }
                is com.intellij.psi.PsiDirectoryContainer -> {
                    // 選取的是 Package 節點（PsiDirectoryContainer 是 PsiPackage 的基類）
                    logger.info("getSelectedVirtualFiles: Found PsiDirectoryContainer: ${psiElement.javaClass.simpleName}")
                    val project = e.project
                    if (project != null) {
                        // 取得 Package 下的所有資料夾
                        val directories = psiElement.directories
                        for (dir in directories) {
                            collectFilesFromDirectory(dir.virtualFile, collectedFiles)
                        }
                        if (collectedFiles.isNotEmpty()) {
                            return collectedFiles.distinct().toTypedArray()
                        }
                    }
                }
                else -> {
                    // 其他 PSI 元素，嘗試取得其所屬檔案
                    psiElement.containingFile?.virtualFile?.let { virtualFile ->
                        logger.info("getSelectedVirtualFiles: Found file from PSI_ELEMENT: ${virtualFile.path}")
                        return arrayOf(virtualFile)
                    }
                }
            }
        }
        
        // 5. 嘗試從 NAVIGATABLE_ARRAY 取得（支援多選）
        e.getData(CommonDataKeys.NAVIGATABLE_ARRAY)?.let { navigatables ->
            for (nav in navigatables) {
                when (nav) {
                    is com.intellij.psi.PsiDirectory -> {
                        collectFilesFromDirectory(nav.virtualFile, collectedFiles)
                    }
                    is com.intellij.psi.PsiElement -> {
                        nav.containingFile?.virtualFile?.let { collectedFiles.add(it) }
                    }
                    is com.intellij.openapi.fileEditor.OpenFileDescriptor -> {
                        collectedFiles.add(nav.file)
                    }
                }
            }
            if (collectedFiles.isNotEmpty()) {
                logger.info("getSelectedVirtualFiles: Found ${collectedFiles.size} files from NAVIGATABLE_ARRAY")
                return collectedFiles.distinct().toTypedArray()
            }
        }
        
        logger.info("getSelectedVirtualFiles: No files found from any source")
        return emptyArray()
    }
    
    /**
     * 遞歸收集資料夾中的所有檔案（不包含子資料夾本身）
     */
    private fun collectFilesFromDirectory(directory: VirtualFile, files: MutableList<VirtualFile>) {
        for (child in directory.children) {
            if (child.isDirectory) {
                collectFilesFromDirectory(child, files)
            } else {
                files.add(child)
            }
        }
    }




    fun performCopyFilesContent(
        e: AnActionEvent,
        filesToCopy: Array<VirtualFile>,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null
    ) {
        val project = e.project ?: return
        val settings = CopyFileContentSettings.getInstance(project) ?: run {
            showNotification("Failed to load settings.", NotificationType.ERROR, project)
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Copying file content…", true) {
            private var payload: CopyPayload? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                payload = buildCopyPayload(project, filesToCopy, settings, customHeaderGenerator, indicator)
            }

            override fun onSuccess() {
                if (project.isDisposed) return
                val result = payload ?: return
                copyToClipboard(result.text)
                showCopyNotifications(project, settings, result)
            }
        })
    }

    private fun buildCopyPayload(
        project: Project,
        filesToCopy: Array<VirtualFile>,
        settings: CopyFileContentSettings,
        customHeaderGenerator: ((VirtualFile, String) -> String)?,
        indicator: ProgressIndicator
    ): CopyPayload {
        // 整段在 BGT 跑，VFS 存取需在 ReadAction 內，否則 2024.3+ strict mode 直接拋 read-lock 錯誤
        return ReadAction.compute<CopyPayload, RuntimeException> {
            val session = CopySession(
                externalLibraryHandler = ExternalLibraryHandler(project),
                pathResolver = ClipboardPathResolver.fromProject(project)
            )
            var totalChars = 0
            var totalLines = 0
            var totalWords = 0
            var totalTokens = 0

            val fileContents = mutableListOf<String>().apply {
                add(ClipboardRestoreParser.escapeContent(settings.state.preText, settings.state.headerFormat))
            }

            for (file in filesToCopy) {
                indicator.checkCanceled()
                if (settings.state.setMaxFileCount && session.fileCount >= settings.state.fileCountLimit) {
                    session.fileLimitReached = true
                    break
                }

                val content = if (file.isDirectory) {
                    processDirectory(file, fileContents, session, project, settings.state.addExtraLineBetweenFiles, customHeaderGenerator)
                } else {
                    processFile(file, fileContents, session, project, settings.state.addExtraLineBetweenFiles, customHeaderGenerator)
                }

                totalChars += content.length
                totalLines += content.count { it == '\n' } + (if (content.isNotEmpty()) 1 else 0)
                totalWords += content.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
                totalTokens += estimateTokens(content)
            }

            fileContents.add(ClipboardRestoreParser.escapeContent(settings.state.postText, settings.state.headerFormat))

            CopyPayload(
                text = fileContents.joinToString(separator = "\n"),
                fileCount = session.fileCount,
                skippedFileSizeCount = session.skippedFileSizeCount,
                fileLimitReached = session.fileLimitReached,
                totalChars = totalChars,
                totalLines = totalLines,
                totalWords = totalWords,
                totalTokens = totalTokens
            )
        }
    }

    @IdeBoundCode
    private fun showCopyNotifications(
        project: Project,
        settings: CopyFileContentSettings,
        result: CopyPayload
    ) {
        if (result.fileLimitReached) {
            val fileLimitWarningMessage = """
                <html>
                <b>File Limit Reached:</b> The file limit of ${settings.state.fileCountLimit} files was reached.
                </html>
            """.trimIndent()
            showNotificationWithSettingsAction(fileLimitWarningMessage, NotificationType.WARNING, project)
        }

        if (!settings.state.showCopyNotification) return

        val fileCountMessage = when {
            result.skippedFileSizeCount > 0 && result.fileCount == 1 ->
                "1 file copied (${result.skippedFileSizeCount} skipped: size exceeded)."
            result.skippedFileSizeCount > 0 ->
                "${result.fileCount} files copied (${result.skippedFileSizeCount} skipped: size exceeded)."
            result.fileCount == 1 -> "1 file copied."
            else -> "${result.fileCount} files copied."
        }

        val statisticsMessage = """
            <html>
            Total characters: ${result.totalChars}<br>
            Total lines: ${result.totalLines}<br>
            Total words: ${result.totalWords}<br>
            Estimated tokens: ${result.totalTokens}
            </html>
        """.trimIndent()

        showNotification(statisticsMessage, NotificationType.INFORMATION, project)
        showNotification("<html><b>$fileCountMessage</b></html>", NotificationType.INFORMATION, project)
    }

    private data class CopyPayload(
        val text: String,
        val fileCount: Int,
        val skippedFileSizeCount: Int,
        val fileLimitReached: Boolean,
        val totalChars: Int,
        val totalLines: Int,
        val totalWords: Int,
        val totalTokens: Int
    )

    private fun estimateTokens(content: String): Int {
        val words = content.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val punctuation = Regex("[;{}()\\[\\],]").findAll(content).count()
        return words.size + punctuation
    }

    private fun processFile(
        file: VirtualFile,
        fileContents: MutableList<String>,
        session: CopySession,
        project: Project,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null
    ): String {
        val settings = CopyFileContentSettings.getInstance(project) ?: return ""
        val handler = session.externalLibraryHandler
        val isExternalLibrary = handler.isFromExternalLibrary(file)
        
        // Determine the file path to display
        val fileRelativePath = when {
            // Project files can be marked as library files when they live under excluded/library roots
            // such as node_modules. Keep those headers project-relative so Paste and Restore can map
            // them back into the current project.
            isExternalLibrary -> {
                CopyPathFormatter.displayPathOrFallback(
                    session.pathResolver,
                    file.path,
                    handler.getCleanPath(file)
                )
            }
            // Then check if it's from project
            !file.isDirectory -> {
                CopyPathFormatter.displayPath(session.pathResolver, file.path)
            }
            // Fallback to presentable URL
            else -> file.presentableUrl
        }

        // Skip already copied files (使用絕對路徑作為去重 key，避免路徑碰撞)
        val dedupeKey = file.path
        if (dedupeKey in session.copiedFilePaths) {
            logger.info("Skipping already copied file: $fileRelativePath")
            return ""
        }

        session.copiedFilePaths.add(dedupeKey)

        var content = ""
        
        // Check filters if enabled
        if (settings.state.useFilters) {
            val fileRelativePathFromRoot = CopyPathFormatter.relativeFilterPath(session.pathResolver, file.path)
            val fileAbsolutePath = file.path
            
            // Get enabled filter rules
            val enabledRules = settings.state.filterRules.filter { it.enabled }
            val includeRules = enabledRules.filter { it.action == CopyFileContentSettings.FilterAction.INCLUDE }
            val excludeRules = enabledRules.filter { it.action == CopyFileContentSettings.FilterAction.EXCLUDE }
            
            // Check excludes first (if exclude filters are enabled)
            if (settings.state.useExcludeFilters && excludeRules.isNotEmpty()) {
                val isExcluded = excludeRules.any { rule ->
                    when (rule.type) {
                        CopyFileContentSettings.FilterType.PATTERN -> {
                            matchesPattern(file.name, rule.value)
                        }
                        CopyFileContentSettings.FilterType.PATH -> {
                            if (PathRuleMatcher.isAbsolutePath(rule.value)) {
                                PathRuleMatcher.matchesPath(fileAbsolutePath, rule.value)
                            } else {
                                fileRelativePathFromRoot != null &&
                                    PathRuleMatcher.matchesPath(fileRelativePathFromRoot, rule.value)
                            }
                        }
                    }
                }
                if (isExcluded) {
                    logger.info("Skipping file: ${file.name} - File is excluded")
                    return ""
                }
            }
            
            // Check includes if specified (if include filters are enabled)
            if (settings.state.useIncludeFilters && includeRules.isNotEmpty()) {
                val matchesInclude = includeRules.any { rule ->
                    when (rule.type) {
                        CopyFileContentSettings.FilterType.PATTERN -> {
                            matchesPattern(file.name, rule.value)
                        }
                        CopyFileContentSettings.FilterType.PATH -> {
                            if (PathRuleMatcher.isAbsolutePath(rule.value)) {
                                PathRuleMatcher.matchesPath(fileAbsolutePath, rule.value)
                            } else {
                                fileRelativePathFromRoot != null &&
                                    PathRuleMatcher.matchesPath(fileRelativePathFromRoot, rule.value)
                            }
                        }
                    }
                }
                if (!matchesInclude) {
                    logger.info("Skipping file: ${file.name} - File does not match any include rule")
                    return ""
                }
            }
        }

        val maxFileSizeBytes = settings.state.maxFileSizeKB * 1024L
        
        // ✅ 修復 OOM 風險：先檢查檔案大小（file.length 只讀 metadata，不載入內容）
        // 這樣可以避免讀取 2GB log 檔等超大檔案導致 IDE 崩潰
        if (file.length > maxFileSizeBytes) {
            logger.info("Skipping file: ${file.name} - File size (${file.length} bytes) exceeds limit ($maxFileSizeBytes bytes)")
            session.skippedFileSizeCount++
            // 回傳提示字串，讓使用者知道哪些檔案被跳過（與 Git 模式保持一致的 UX）
            val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
            fileContents.add(header)
            fileContents.add("// File skipped: size exceeds limit (${file.length} bytes)")
            if (addExtraLine) {
                fileContents.add("")
            }
            return ""
        }
        
        // Handle external library files differently
        if (isExternalLibrary) {
            // Check if file should be processed
            if (!handler.shouldProcessFile(file)) {
                logger.info("Skipping external library file: ${file.name}")
                return ""
            }
            
            // Try to read content from external library (size already checked above)
            content = handler.readContent(file) ?: ""
            
            if (content.isNotEmpty()) {
                val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                    ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
                fileContents.add(header)
                fileContents.add(ClipboardRestoreParser.escapeContent(content, settings.state.headerFormat))
                session.fileCount++
                if (addExtraLine) {
                    fileContents.add("")
                }
            } else {
                logger.info("Skipping file: ${file.name} - Could not read content from external library")
            }
        } else {
            // Handle regular project files (size already checked above)
            if (!isBinaryFile(file)) {
                content = readFileContents(file)

                if (content.isNotEmpty()) {
                    val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                        ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
                    fileContents.add(header)
                    fileContents.add(ClipboardRestoreParser.escapeContent(content, settings.state.headerFormat))
                    session.fileCount++
                    if (addExtraLine) {
                        fileContents.add("")
                    }
                } else {
                    logger.info("Skipping file: ${file.name} - Could not read content")
                }
            } else {
                logger.info("Skipping file: ${file.name} - Binary file")
            }
        }
        return content
    }

    private fun processDirectory(
        directory: VirtualFile,
        fileContents: MutableList<String>,
        session: CopySession,
        project: Project,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null
    ): String {
        val directoryContent = StringBuilder(1024) // Pre-allocate for better performance
        val settings = CopyFileContentSettings.getInstance(project) ?: return ""
        
        // Check if directory should be processed based on filters
        if (settings.state.useFilters) {
            val dirRelativePath = CopyPathFormatter.relativeFilterPath(session.pathResolver, directory.path)
            val dirAbsolutePath = directory.path
            
            // Get enabled filter rules
            val enabledRules = settings.state.filterRules.filter { it.enabled }
            val includePathRules = enabledRules.filter { 
                it.action == CopyFileContentSettings.FilterAction.INCLUDE && 
                it.type == CopyFileContentSettings.FilterType.PATH 
            }
            val excludePathRules = enabledRules.filter { 
                it.action == CopyFileContentSettings.FilterAction.EXCLUDE && 
                it.type == CopyFileContentSettings.FilterType.PATH 
            }
            
            // Check excludes first
            if (settings.state.useExcludeFilters && excludePathRules.isNotEmpty()) {
                val isExcluded = excludePathRules.any { rule ->
                    if (PathRuleMatcher.isAbsolutePath(rule.value)) {
                        PathRuleMatcher.matchesPath(dirAbsolutePath, rule.value)
                    } else {
                        dirRelativePath != null &&
                            PathRuleMatcher.matchesPath(dirRelativePath, rule.value)
                    }
                }
                if (isExcluded) {
                    logger.info("Skipping directory: ${directory.name} - Directory is excluded")
                    return ""
                }
            }
            
            // Check includes if specified
            if (settings.state.useIncludeFilters && includePathRules.isNotEmpty()) {
                val shouldProcess = includePathRules.any { rule ->
                    if (PathRuleMatcher.isAbsolutePath(rule.value)) {
                        PathRuleMatcher.overlapsDirectory(dirAbsolutePath, rule.value)
                    } else {
                        dirRelativePath != null &&
                            PathRuleMatcher.overlapsDirectory(dirRelativePath, rule.value)
                    }
                }
                if (!shouldProcess) {
                    logger.info("Skipping directory: ${directory.name} - Directory does not match any include path")
                    return ""
                }
            }
        }

        for (childFile in directory.children) {
            if (settings.state.setMaxFileCount && session.fileCount >= settings.state.fileCountLimit) {
                session.fileLimitReached = true
                break
            }
            val content = if (childFile.isDirectory) {
                processDirectory(childFile, fileContents, session, project, addExtraLine, customHeaderGenerator)
            } else {
                processFile(childFile, fileContents, session, project, addExtraLine, customHeaderGenerator)
            }
            if (content.isNotEmpty()) {
                directoryContent.append(content)
            }
        }

        return directoryContent.toString()
    }

    @IdeBoundCode
    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val data = StringSelection(text)
        clipboard.setContents(data, null)
    }

    @IdeBoundCode
    private fun readFileContents(file: VirtualFile): String {
        return try {
            // VfsUtilCore.loadText 會依檔案編碼解碼，避免強制 UTF-8 造成中文/big5/sjis 亂碼
            VfsUtilCore.loadText(file)
        } catch (e: Exception) {
            logger.error("Failed to read file contents: ${e.message}")
            ""
        }
    }

    @IdeBoundCode
    private fun isBinaryFile(file: VirtualFile): Boolean = file.fileType.isBinary
    
    private fun matchesPattern(fileName: String, pattern: String): Boolean {
        return try {
            // Convert wildcard pattern to regex if needed
            val regexPattern = if (pattern.contains("*") || pattern.contains("?")) {
                pattern.replace(".", "\\.")
                    .replace("*", ".*")
                    .replace("?", ".")
            } else {
                pattern
            }
            fileName.matches(Regex(regexPattern))
        } catch (e: Exception) {
            // If pattern is invalid, try simple contains match
            fileName.contains(pattern)
        }
    }

    companion object {
        @IdeBoundCode
        fun showNotification(
            message: String,
            notificationType: NotificationType,
            project: Project?
        ): com.intellij.notification.Notification {
            val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("ClipCode")
            val notification = notificationGroup.createNotification(message, notificationType).setImportant(true)
            notification.notify(project)
            return notification
        }
    }

    private data class CopySession(
        val copiedFilePaths: MutableSet<String> = mutableSetOf(),
        val externalLibraryHandler: ExternalLibraryHandler,
        val pathResolver: ClipboardPathResolver,
        var fileCount: Int = 0,
        var skippedFileSizeCount: Int = 0,
        var fileLimitReached: Boolean = false
    )

    @IdeBoundCode
    private fun showNotificationWithSettingsAction(message: String, notificationType: NotificationType, project: Project?) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("ClipCode")
        val notification = notificationGroup.createNotification(message, notificationType).setImportant(true)
        notification.addAction(NotificationAction.createSimple("Go to Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "ClipCode Settings")
        })
        notification.notify(project)
    }
}
