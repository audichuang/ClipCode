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

        /**
         * Generic fallback pattern to detect "file:" headers regardless of prefix style.
         * Matches: // file: xxx, # file: xxx, file: xxx, etc.
         */
        private val GENERIC_FILE_HEADER = Regex("^(?://|#|/\\*)?\\s*file:\\s*(.+?)\\s*(?:\\*/)?$", RegexOption.IGNORE_CASE)
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

        // Pre-check which files already exist (BEFORE WriteCommandAction)
        val existingFiles = mutableListOf<String>()
        for (parsedFile in filesToCreate) {
            val relativePath = normalizePathForCurrentPlatform(parsedFile.path.trim())
            if (relativePath.isEmpty()) continue

            val pathParts = relativePath.split("/")
            val fileName = pathParts.last()
            val directoryPath = pathParts.dropLast(1).joinToString("/")

            // Navigate to the target directory (if it exists)
            var currentDir: VirtualFile? = projectRoot
            if (directoryPath.isNotEmpty()) {
                val dirParts = directoryPath.split("/").filter { it.isNotEmpty() }
                for (dirName in dirParts) {
                    currentDir = currentDir?.findChild(dirName)
                    if (currentDir == null || !currentDir.isDirectory) break
                }
            }

            // Check if file exists
            if (currentDir != null && currentDir.findChild(fileName) != null) {
                existingFiles.add(relativePath)
            }
        }

        // Show single dialog for all existing files (OUTSIDE WriteCommandAction)
        var overwriteAll = false
        var skipAll = false

        if (existingFiles.isNotEmpty()) {
            val existingList = existingFiles.take(10).joinToString("\n") { "• $it" }
            val moreNote = if (existingFiles.size > 10) "\n... and ${existingFiles.size - 10} more" else ""
            val dialogMessage = "The following ${existingFiles.size} file(s) already exist:\n\n$existingList$moreNote\n\nWhat would you like to do?"

            val result = Messages.showDialog(
                project,
                dialogMessage,
                "Files Already Exist",
                arrayOf("Overwrite All", "Skip Existing", "Cancel"),
                0,
                Messages.getWarningIcon()
            )

            when (result) {
                0 -> overwriteAll = true  // Overwrite All
                1 -> skipAll = true       // Skip Existing
                else -> return            // Cancel
            }
        }

        // Create files
        var successCount = 0
        var skipCount = 0
        var failCount = 0
        val errors = mutableListOf<String>()

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                filesToCreate.forEach { parsedFile ->
                    try {
                        val created = createFile(project, projectRoot, parsedFile, overwriteAll, skipAll)
                        if (created) {
                            successCount++
                        } else {
                            skipCount++
                        }
                    } catch (e: Exception) {
                        failCount++
                        errors.add("${parsedFile.path}: ${e.message}")
                        logger.error("Failed to create file: ${parsedFile.path}", e)
                    }
                }

                // Show result notification
                if (successCount > 0) {
                    val skipNote = if (skipCount > 0) " ($skipCount skipped)" else ""
                    CopyFileContentAction.showNotification(
                        "<html><b>Successfully restored $successCount file(s)$skipNote</b></html>",
                        NotificationType.INFORMATION,
                        project
                    )
                }
                
                if (failCount > 0) {
                    val errorMessage = "<html><b>Failed to restore $failCount file(s):</b><br>" +
                        errors.take(5).joinToString("<br>") { it } +
                        (if (errors.size > 5) "<br>..." else "") +
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

        val customRegex = Regex(headerPattern)
        val lines = content.lines()

        var currentFilePath: String? = null
        var currentIsDeleted = false
        val currentContent = StringBuilder(512)

        for (line in lines) {
            // Try custom pattern first, then fallback to generic pattern
            var match = customRegex.find(line)
            if (match == null || match.groups.size <= 1) {
                match = GENERIC_FILE_HEADER.find(line)
            }

            if (match != null && match.groups.size > 1) {
                // Found a file header - save previous file (allow empty content for files like .gitkeep)
                if (currentFilePath != null) {
                    files.add(ParsedFile(
                        path = currentFilePath,
                        content = currentContent.toString().trim(),
                        isDeleted = currentIsDeleted
                    ))
                }

                // Extract path and strip Git labels
                val rawPath = match.groups[1]?.value ?: continue
                // Check if ANY label is DELETED (not just the first one)
                currentIsDeleted = rawPath.contains("[DELETED]")
                val pathWithoutLabel = stripChangeTypeLabel(rawPath)
                currentFilePath = normalizePathForCurrentPlatform(pathWithoutLabel)
                currentContent.clear()
            } else if (currentFilePath != null) {
                if (currentContent.isNotEmpty()) {
                    currentContent.append("\n")
                }
                currentContent.append(line)
            }
        }

        // Don't forget the last file (allow empty content)
        if (currentFilePath != null) {
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

    /**
     * Create a file from parsed content.
     * @return true if file was created/overwritten, false if skipped
     */
    private fun createFile(
        project: Project,
        projectRoot: VirtualFile,
        parsedFile: ParsedFile,
        overwriteExisting: Boolean,
        skipExisting: Boolean
    ): Boolean {
        val relativePath = normalizePathForCurrentPlatform(parsedFile.path.trim())

        // Validate the normalized path
        if (relativePath.isEmpty()) {
            throw IllegalArgumentException("Empty file path after normalization from: '${parsedFile.path}'")
        }

        // Split path into directory and filename
        val pathParts = relativePath.split("/")
        val fileName = pathParts.last()
        val directoryPath = pathParts.dropLast(1).joinToString("/")

        // Validate file name - allow square brackets for Next.js dynamic routes
        if (fileName.isEmpty() || fileName.contains(Regex("[<>:\"|?*]"))) {
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
            if (skipExisting) {
                return false  // Skip this file
            }
            if (overwriteExisting) {
                VfsUtil.saveText(existingFile, parsedFile.content)
                return true
            }
            // Should not reach here if pre-check was done correctly
            return false
        } else {
            // Create new file
            val newFile = currentDir.createChildData(this, fileName)
            VfsUtil.saveText(newFile, parsedFile.content)
            return true
        }
    }
    
    private fun normalizePathForCurrentPlatform(path: String): String {
        var normalizedPath = path.trim()

        // Handle Windows absolute paths with backslash or forward slash (e.g., D:\Users\... or D:/Users/...)
        if (normalizedPath.matches(Regex("^[a-zA-Z]:[/\\\\].+"))) {
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

        // Remove empty segments, path traversal attempts (..), current directory (.),
        // and invalid characters (but allow square brackets for Next.js dynamic routes)
        val segments = normalizedPath.split("/").filter { segment ->
            segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&  // Path traversal protection
            !segment.contains(Regex("[<>:\"|?*]"))  // Allow square brackets []
        }

        return segments.joinToString("/")
    }
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}