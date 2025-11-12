package com.github.mwguerra.copyfilecontent

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiDirectory
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

class PasteAndRestoreFilesAction : AnAction() {
    private val logger = Logger.getInstance(PasteAndRestoreFilesAction::class.java)

    companion object {
        /**
         * Regex pattern to match one or more consecutive change type labels at the start of a path.
         * Handles multiple labels like "[MODIFIED] [NEW] path" and optional whitespace.
         * Non-capturing group (?:...) for efficiency.
         */
        private val CHANGE_TYPE_PATTERN = Regex("^(?:\\[(NEW|MODIFIED|DELETED|MOVED)\\]\\s*)+")

        /**
         * Regex pattern to extract ONLY the first change type label.
         * Used for determining isDeleted flag to avoid false positives.
         */
        private val FIRST_LABEL_PATTERN = Regex("^\\[(NEW|MODIFIED|DELETED|MOVED)\\]")
    }

    data class ParsedFile(
        val path: String,
        val content: String,
        val isDeleted: Boolean = false
    )
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: run {
            CopyFileContentAction.showNotification(
                "No project found. Action cannot proceed.", 
                NotificationType.ERROR, 
                null
            )
            return
        }
        
        // Get clipboard content
        val clipboardContent = getClipboardContent()
        if (clipboardContent.isNullOrBlank()) {
            CopyFileContentAction.showNotification(
                "Clipboard is empty or doesn't contain text.", 
                NotificationType.WARNING, 
                project
            )
            return
        }
        
        // Get the header format from settings
        val settings = CopyFileContentSettings.getInstance(project) ?: run {
            CopyFileContentAction.showNotification(
                "Failed to load settings.", 
                NotificationType.ERROR, 
                project
            )
            return
        }
        
        // Parse the clipboard content to extract files
        val parsedFiles = parseClipboardContent(clipboardContent, settings.state.headerFormat)

        if (parsedFiles.isEmpty()) {
            CopyFileContentAction.showNotification(
                "No files found in clipboard content. Make sure the content was copied using Copy File Content plugin.",
                NotificationType.WARNING,
                project
            )
            return
        }

        // Filter out deleted files
        val filesToCreate = parsedFiles.filter { !it.isDeleted }
        val deletedFiles = parsedFiles.filter { it.isDeleted }

        // Log information about deleted files
        if (deletedFiles.isNotEmpty()) {
            val deletedList = deletedFiles.joinToString(", ") { it.path }
            logger.info("Skipping ${deletedFiles.size} deleted file(s): $deletedList")
        }

        // Check if there are any files to restore
        if (filesToCreate.isEmpty()) {
            val message = if (deletedFiles.size == 1) {
                "The file in clipboard is marked as [DELETED].\nDeleted files cannot be restored."
            } else {
                "All ${deletedFiles.size} files in clipboard are marked as [DELETED].\nDeleted files cannot be restored."
            }
            CopyFileContentAction.showNotification(
                message,
                NotificationType.WARNING,
                project
            )
            return
        }

        // Show confirmation dialog with file list
        val fileList = filesToCreate.joinToString("\n") { "• ${it.path}" }
        val deletedNote = if (deletedFiles.isNotEmpty()) {
            "\n\n(Skipped ${deletedFiles.size} deleted file${if (deletedFiles.size > 1) "s" else ""})"
        } else {
            ""
        }
        val message = "Found ${filesToCreate.size} file(s) to restore:\n\n$fileList$deletedNote\n\nDo you want to create these files?"
        
        val result = Messages.showYesNoDialog(
            project,
            message,
            "Restore Files from Clipboard",
            Messages.getQuestionIcon()
        )
        
        if (result != Messages.YES) {
            return
        }
        
        // Get project root
        val projectRoot = com.intellij.openapi.roots.ProjectRootManager.getInstance(project)
            .contentRoots.firstOrNull()
        
        if (projectRoot == null) {
            CopyFileContentAction.showNotification(
                "Could not find project root directory.", 
                NotificationType.ERROR, 
                project
            )
            return
        }
        
        // Create files
        var successCount = 0
        var failCount = 0
        val errors = mutableListOf<String>()
        
        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                filesToCreate.forEach { parsedFile ->
                    try {
                        createFile(project, projectRoot, parsedFile)
                        successCount++
                    } catch (e: Exception) {
                        failCount++
                        errors.add("${parsedFile.path}: ${e.message}")
                        logger.error("Failed to create file: ${parsedFile.path}", e)
                    }
                }
                
                // Show result notification
                if (successCount > 0) {
                    CopyFileContentAction.showNotification(
                        "<html><b>Successfully restored $successCount file(s)</b></html>", 
                        NotificationType.INFORMATION, 
                        project
                    )
                }
                
                if (failCount > 0) {
                    val errorMessage = "<html><b>Failed to restore $failCount file(s):</b><br>" +
                        errors.take(5).joinToString("<br>") { it } +
                        if (errors.size > 5) "<br>..." else "" +
                        "</html>"
                    CopyFileContentAction.showNotification(
                        errorMessage, 
                        NotificationType.ERROR, 
                        project
                    )
                }
            }
        }
    }
    
    private fun getClipboardContent(): String? {
        return try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as String
            } else {
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to get clipboard content", e)
            null
        }
    }
    
    private fun parseClipboardContent(content: String, headerFormat: String): List<ParsedFile> {
        val files = mutableListOf<ParsedFile>()
        
        // Convert header format to regex pattern
        val headerPattern = headerFormat
            .replace("$", "\\$")
            .replace(".", "\\.")
            .replace("*", "\\*")
            .replace("+", "\\+")
            .replace("?", "\\?")
            .replace("[", "\\[")
            .replace("]", "\\]")
            .replace("(", "\\(")
            .replace(")", "\\)")
            .replace("{", "\\{")
            .replace("}", "\\}")
            .replace("\\\$FILE_PATH", "(.+)")
        
        val regex = Regex(headerPattern)
        val lines = content.lines()
        
        var currentFilePath: String? = null
        var currentIsDeleted = false
        val currentContent = StringBuilder(512) // Pre-allocate for better performance

        for (line in lines) {
            val match = regex.find(line)
            if (match != null && match.groups.size > 1) {
                // Found a file header
                if (currentFilePath != null && currentContent.isNotEmpty()) {
                    // Save previous file
                    files.add(ParsedFile(
                        path = currentFilePath,
                        content = currentContent.toString().trim(),
                        isDeleted = currentIsDeleted
                    ))
                }
                // Start new file - extract first label for deletion check, strip all labels, normalize path
                val rawPath = match.groups[1]?.value ?: continue

                // Check ONLY the first label to determine if deleted (avoid false positives)
                val firstLabel = FIRST_LABEL_PATTERN.find(rawPath)?.value
                currentIsDeleted = firstLabel == "[DELETED]"

                val pathWithoutLabel = stripChangeTypeLabel(rawPath)
                currentFilePath = normalizePathForCurrentPlatform(pathWithoutLabel)
                currentContent.clear()
            } else if (currentFilePath != null) {
                // Add content to current file
                if (currentContent.isNotEmpty()) {
                    currentContent.append("\n")
                }
                currentContent.append(line)
            }
        }

        // Don't forget the last file
        if (currentFilePath != null && currentContent.isNotEmpty()) {
            files.add(ParsedFile(
                path = currentFilePath,
                content = currentContent.toString().trim(),
                isDeleted = currentIsDeleted
            ))
        }
        
        return files
    }

    /**
     * Strip change type labels ([NEW], [MODIFIED], [DELETED], [MOVED]) from the beginning of a file path.
     * These labels are added by CopyGitFilesContentAction when copying from Git changes view.
     *
     * Handles edge cases:
     * - Multiple consecutive labels: "[MODIFIED] [NEW] path" → "path"
     * - Optional whitespace: "[NEW]path" → "path" (no space after label)
     * - Preserves labels not at start: "src/[DELETED]/file" → "src/[DELETED]/file"
     *
     * @param path The file path potentially containing change type labels
     * @return The path with all leading change type labels removed
     */
    private fun stripChangeTypeLabel(path: String): String {
        // Use pre-compiled pattern from companion object
        // Pattern matches one or more consecutive labels with optional whitespace
        val stripped = CHANGE_TYPE_PATTERN.replace(path, "").trim()

        // Validate that we actually have a path
        if (stripped.isBlank()) {
            logger.warn("Invalid file path: contains only change type label(s): '$path'")
            return path // Return original if stripping results in empty string
        }

        logger.debug("Stripped change type labels from '$path' to '$stripped'")
        return stripped
    }

    private fun createFile(project: Project, projectRoot: VirtualFile, parsedFile: ParsedFile) {
        val relativePath = normalizePathForCurrentPlatform(parsedFile.path.trim())
        
        // Validate the normalized path
        if (relativePath.isEmpty()) {
            throw IllegalArgumentException("Empty file path after normalization from: '${parsedFile.path}'")
        }
        
        // Split path into directory and filename
        val pathParts = relativePath.split("/")
        val fileName = pathParts.last()
        val directoryPath = pathParts.dropLast(1).joinToString("/")
        
        // Additional validation for file name
        // Include square brackets in validation to catch un-stripped labels or invalid characters
        if (fileName.isEmpty() || fileName.contains(Regex("[<>:\"|?*\\[\\]]"))) {
            throw IllegalArgumentException("Invalid file name: '$fileName' in path: '$relativePath'")
        }
        
        // Create directory structure if needed
        var currentDir: VirtualFile = projectRoot
        if (directoryPath.isNotEmpty()) {
            val dirParts = directoryPath.split("/").filter { it.isNotEmpty() }
            for (dirName in dirParts) {
                val existingDir = currentDir.findChild(dirName)
                currentDir = if (existingDir != null && existingDir.isDirectory) {
                    existingDir
                } else {
                    currentDir.createChildDirectory(this, dirName)
                }
            }
        }
        
        // Check if file already exists
        val existingFile = currentDir.findChild(fileName)
        if (existingFile != null) {
            // Ask user what to do
            val result = Messages.showYesNoCancelDialog(
                project,
                "File '$relativePath' already exists. Overwrite?",
                "File Exists",
                "Overwrite",
                "Skip",
                "Cancel All",
                Messages.getWarningIcon()
            )
            
            when (result) {
                Messages.YES -> {
                    // Overwrite the file
                    VfsUtil.saveText(existingFile, parsedFile.content)
                }
                Messages.NO -> {
                    // Skip this file
                    return
                }
                Messages.CANCEL -> {
                    // Cancel all operations
                    throw RuntimeException("Operation cancelled by user")
                }
            }
        } else {
            // Create new file
            val newFile = currentDir.createChildData(this, fileName)
            VfsUtil.saveText(newFile, parsedFile.content)
        }
    }
    
    private fun normalizePathForCurrentPlatform(path: String): String {
        var normalizedPath = path.trim()
        
        // Handle Windows absolute paths (e.g., D:\Users\...)
        if (normalizedPath.matches(Regex("^[a-zA-Z]:\\\\.+"))) {
            // Remove drive letter and colon (e.g., "D:" -> "")
            normalizedPath = normalizedPath.substring(2)
        }
        
        // Convert all backslashes to forward slashes for consistent handling
        normalizedPath = normalizedPath.replace('\\', '/')
        
        // Remove leading slash if present (to ensure relative path)
        if (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1)
        }
        
        // Clean up multiple consecutive slashes
        normalizedPath = normalizedPath.replace(Regex("/+"), "/")
        
        // Remove empty segments and invalid characters
        // Include square brackets to catch un-stripped labels or invalid characters
        val segments = normalizedPath.split("/").filter { segment ->
            segment.isNotEmpty() && segment != "." && !segment.contains(Regex("[<>:\"|?*\\[\\]]"))
        }
        
        return segments.joinToString("/")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}