package com.github.audichuang.clipcode

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

class CopyFileContentAction : AnAction() {
    private var fileCount = 0
    private var fileLimitReached = false
    private val logger = Logger.getInstance(CopyFileContentAction::class.java)
    private var externalLibraryHandler: ExternalLibraryHandler? = null

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            showNotification("No project found. Action cannot proceed.", NotificationType.ERROR, null)
            return
        }
        val selectedFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: run {
            showNotification("No files selected.", NotificationType.ERROR, project)
            return
        }

        performCopyFilesContent(e, selectedFiles)
    }

    fun performCopyFilesContent(
        e: AnActionEvent,
        filesToCopy: Array<VirtualFile>,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null
    ) {
        fileCount = 0
        fileLimitReached = false
        var totalChars = 0
        var totalLines = 0
        var totalWords = 0
        var totalTokens = 0
        val copiedFilePaths = mutableSetOf<String>()

        val project = e.project ?: return
        // Initialize external library handler
        externalLibraryHandler = ExternalLibraryHandler(project)
        val settings = CopyFileContentSettings.getInstance(project) ?: run {
            showNotification("Failed to load settings.", NotificationType.ERROR, project)
            return
        }

        val fileContents = mutableListOf<String>().apply {
            add(settings.state.preText)
        }

        for (file in filesToCopy) {
            // Check file limit only if the checkbox is selected.
            if (settings.state.setMaxFileCount && fileCount >= settings.state.fileCountLimit) {
                fileLimitReached = true
                break
            }

            val content = if (file.isDirectory) {
                processDirectory(file, fileContents, copiedFilePaths, project, settings.state.addExtraLineBetweenFiles, customHeaderGenerator)
            } else {
                processFile(file, fileContents, copiedFilePaths, project, settings.state.addExtraLineBetweenFiles, customHeaderGenerator)
            }

            totalChars += content.length
            totalLines += content.count { it == '\n' } + (if (content.isNotEmpty()) 1 else 0)
            totalWords += content.split("\\s+".toRegex()).filter { it.isNotEmpty() }.size
            totalTokens += estimateTokens(content)
        }

        fileContents.add(settings.state.postText)
        copyToClipboard(fileContents.joinToString(separator = "\n"))

        if (fileLimitReached) {
            val fileLimitWarningMessage = """
                <html>
                <b>File Limit Reached:</b> The file limit of ${settings.state.fileCountLimit} files was reached.
                </html>
            """.trimIndent()
            showNotificationWithSettingsAction(fileLimitWarningMessage, NotificationType.WARNING, project)
        }

        if (settings.state.showCopyNotification) {
            val fileCountMessage = when (fileCount) {
                1 -> "1 file copied."
                else -> "$fileCount files copied."
            }

            val statisticsMessage = """
                <html>
                Total characters: $totalChars<br>
                Total lines: $totalLines<br>
                Total words: $totalWords<br>
                Estimated tokens: $totalTokens
                </html>
            """.trimIndent()

            showNotification(statisticsMessage, NotificationType.INFORMATION, project)
            showNotification("<html><b>$fileCountMessage</b></html>", NotificationType.INFORMATION, project)
        }
    }

    private fun estimateTokens(content: String): Int {
        val words = content.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        val punctuation = Regex("[;{}()\\[\\],]").findAll(content).count()
        return words.size + punctuation
    }

    private fun processFile(
        file: VirtualFile,
        fileContents: MutableList<String>,
        copiedFilePaths: MutableSet<String>,
        project: Project,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null
    ): String {
        val settings = CopyFileContentSettings.getInstance(project) ?: return ""
        val repositoryRoot = getRepositoryRoot(project)
        val handler = externalLibraryHandler
        
        // Determine the file path to display
        val fileRelativePath = when {
            // First check if it's from external library
            handler != null && handler.isFromExternalLibrary(file) -> {
                handler.getCleanPath(file)
            }
            // Then check if it's from project
            repositoryRoot != null && VfsUtil.getRelativePath(file, repositoryRoot, '/') != null -> {
                VfsUtil.getRelativePath(file, repositoryRoot, '/')!!
            }
            // Fallback to presentable URL
            else -> file.presentableUrl
        }

        // Skip already copied files
        if (fileRelativePath in copiedFilePaths) {
            logger.info("Skipping already copied file: $fileRelativePath")
            return ""
        }

        copiedFilePaths.add(fileRelativePath)

        var content = ""
        
        // Check filters if enabled
        if (settings.state.useFilters) {
            val projectRoot = getRepositoryRoot(project)
            val fileRelativePathFromRoot = projectRoot?.let { VfsUtil.getRelativePath(file, it, '/') }
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
                            if (rule.value.startsWith("/")) {
                                fileAbsolutePath.startsWith(rule.value) || fileAbsolutePath == rule.value
                            } else {
                                fileRelativePathFromRoot != null && (
                                    fileRelativePathFromRoot.startsWith(rule.value) ||
                                    fileRelativePathFromRoot == rule.value
                                )
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
                            if (rule.value.startsWith("/")) {
                                fileAbsolutePath.startsWith(rule.value) || fileAbsolutePath == rule.value
                            } else {
                                fileRelativePathFromRoot != null && (
                                    fileRelativePathFromRoot.startsWith(rule.value) ||
                                    fileRelativePathFromRoot == rule.value
                                )
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
        
        // Handle external library files differently
        if (handler != null && handler.isFromExternalLibrary(file)) {
            // Check if file should be processed
            if (!handler.shouldProcessFile(file)) {
                logger.info("Skipping external library file: ${file.name}")
                return ""
            }
            
            // Try to read content from external library
            content = handler.readContent(file) ?: ""
            
            if (content.isNotEmpty() && content.length <= maxFileSizeBytes) {
                val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                    ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
                fileContents.add(header)
                fileContents.add(content)
                fileCount++
                if (addExtraLine) {
                    fileContents.add("")
                }
            } else if (content.isEmpty()) {
                logger.info("Skipping file: ${file.name} - Could not read content from external library")
            } else {
                logger.info("Skipping file: ${file.name} - Size limit exceeded (${content.length} bytes)")
            }
        } else {
            // Handle regular project files
            if (!isBinaryFile(file)) {
                content = readFileContents(file)

                if (content.isNotEmpty() && content.length <= maxFileSizeBytes) {
                    val header = customHeaderGenerator?.invoke(file, fileRelativePath)
                        ?: settings.state.headerFormat.replace("\$FILE_PATH", fileRelativePath)
                    fileContents.add(header)
                    fileContents.add(content)
                    fileCount++
                    if (addExtraLine) {
                        fileContents.add("")
                    }
                } else if (content.isEmpty()) {
                    logger.info("Skipping file: ${file.name} - Could not read content")
                } else {
                    logger.info("Skipping file: ${file.name} - Size limit exceeded (${content.length} bytes)")
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
        copiedFilePaths: MutableSet<String>,
        project: Project,
        addExtraLine: Boolean,
        customHeaderGenerator: ((VirtualFile, String) -> String)? = null
    ): String {
        val directoryContent = StringBuilder(1024) // Pre-allocate for better performance
        val settings = CopyFileContentSettings.getInstance(project) ?: return ""
        
        // Check if directory should be processed based on filters
        if (settings.state.useFilters) {
            val projectRoot = getRepositoryRoot(project)
            val dirRelativePath = projectRoot?.let { VfsUtil.getRelativePath(directory, it, '/') }
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
                    if (rule.value.startsWith("/")) {
                        dirAbsolutePath.startsWith(rule.value) || dirAbsolutePath == rule.value
                    } else {
                        dirRelativePath != null && (
                            dirRelativePath.startsWith(rule.value) ||
                            dirRelativePath == rule.value
                        )
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
                    if (rule.value.startsWith("/")) {
                        dirAbsolutePath.startsWith(rule.value) || 
                        rule.value.startsWith(dirAbsolutePath)
                    } else {
                        dirRelativePath != null && (
                            dirRelativePath.startsWith(rule.value) ||
                            rule.value.startsWith(dirRelativePath) ||
                            dirRelativePath == rule.value
                        )
                    }
                }
                if (!shouldProcess) {
                    logger.info("Skipping directory: ${directory.name} - Directory does not match any include path")
                    return ""
                }
            }
        }

        for (childFile in directory.children) {
            if (settings.state.setMaxFileCount && fileCount >= settings.state.fileCountLimit) {
                fileLimitReached = true
                break
            }
            val content = if (childFile.isDirectory) {
                processDirectory(childFile, fileContents, copiedFilePaths, project, addExtraLine, customHeaderGenerator)
            } else {
                processFile(childFile, fileContents, copiedFilePaths, project, addExtraLine, customHeaderGenerator)
            }
            if (content.isNotEmpty()) {
                directoryContent.append(content)
            }
        }

        return directoryContent.toString()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val data = StringSelection(text)
        clipboard.setContents(data, null)
    }

    private fun readFileContents(file: VirtualFile): String {
        return try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            logger.error("Failed to read file contents: ${e.message}")
            ""
        }
    }

    private fun isBinaryFile(file: VirtualFile): Boolean {
        return FileTypeManager.getInstance().getFileTypeByFile(file).isBinary
    }

    private fun getRepositoryRoot(project: Project): VirtualFile? {
        val projectRootManager = ProjectRootManager.getInstance(project)
        return projectRootManager.contentRoots.firstOrNull()
    }
    
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

    private fun showNotificationWithSettingsAction(message: String, notificationType: NotificationType, project: Project?) {
        val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("ClipCode")
        val notification = notificationGroup.createNotification(message, notificationType).setImportant(true)
        notification.addAction(NotificationAction.createSimple("Go to Settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "ClipCode Settings")
        })
        notification.notify(project)
    }
}