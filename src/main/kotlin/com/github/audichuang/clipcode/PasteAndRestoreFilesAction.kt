package com.github.audichuang.clipcode

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

    /**
     * Represents a file to be deleted during paste operation.
     */
    data class DeletionTarget(
        val relativePath: String,
        val virtualFile: VirtualFile?,
        val exists: Boolean
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
                "No files found in clipboard content. Make sure the content was copied using ClipCode plugin.",
                NotificationType.WARNING,
                project
            )
            return
        }

        // Filter out deleted files
        val filesToCreate = parsedFiles.filter { !it.isDeleted }
        val deletedFiles = parsedFiles.filter { it.isDeleted }

        // Get project root early (needed for deletion target discovery)
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

        // Find deletion targets (files that exist and can be deleted)
        val deletionTargets = if (deletedFiles.isNotEmpty()) {
            findDeletionTargets(projectRoot, deletedFiles)
        } else {
            emptyList()
        }
        val existingDeletionTargets = deletionTargets.filter { it.exists }
        val missingDeletionTargets = deletionTargets.filter { !it.exists }

        // Log information about files
        if (deletedFiles.isNotEmpty()) {
            val deletedList = deletedFiles.joinToString(", ") { it.path }
            logger.info("Found ${deletedFiles.size} deleted file(s): $deletedList")
            if (missingDeletionTargets.isNotEmpty()) {
                logger.info("${missingDeletionTargets.size} [DELETED] file(s) not found in project (already deleted or never existed)")
            }
        }

        // Check if there are any actionable operations
        if (filesToCreate.isEmpty() && existingDeletionTargets.isEmpty()) {
            val message = when {
                deletedFiles.isEmpty() -> "No files found in clipboard content."
                missingDeletionTargets.isNotEmpty() ->
                    "All ${missingDeletionTargets.size} file(s) marked as [DELETED] were not found in the project.\nNothing to do."
                else -> "No actionable files in clipboard."
            }
            CopyFileContentAction.showNotification(
                message,
                NotificationType.WARNING,
                project
            )
            return
        }

        // Build confirmation message
        val createSection = if (filesToCreate.isNotEmpty()) {
            val createList = filesToCreate.take(15).joinToString("\n") { "  + ${it.path}" }
            val createMore = if (filesToCreate.size > 15) "\n  ... and ${filesToCreate.size - 15} more" else ""
            "Files to CREATE (${filesToCreate.size}):\n$createList$createMore"
        } else {
            null
        }

        val deleteSection = if (existingDeletionTargets.isNotEmpty()) {
            val deleteList = existingDeletionTargets.take(15).joinToString("\n") { "  - ${it.relativePath}" }
            val deleteMore = if (existingDeletionTargets.size > 15) "\n  ... and ${existingDeletionTargets.size - 15} more" else ""
            "Files to DELETE (${existingDeletionTargets.size}):\n$deleteList$deleteMore"
        } else {
            null
        }

        val skippedNote = if (missingDeletionTargets.isNotEmpty()) {
            "(${missingDeletionTargets.size} deleted file(s) not found in project - skipped)"
        } else {
            null
        }

        val confirmMessage = listOfNotNull(createSection, deleteSection, skippedNote)
            .joinToString("\n\n") + "\n\nDo you want to proceed?"

        val dialogTitle = when {
            filesToCreate.isNotEmpty() && existingDeletionTargets.isNotEmpty() -> "Restore and Delete Files"
            existingDeletionTargets.isNotEmpty() -> "Delete Files"
            else -> "Restore Files from Clipboard"
        }

        val result = Messages.showYesNoDialog(
            project,
            confirmMessage,
            dialogTitle,
            "Proceed",
            "Cancel",
            Messages.getQuestionIcon()
        )

        if (result != Messages.YES) {
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

        // Track deletion results
        var deleteSuccessCount = 0
        var deleteFailCount = 0
        val deleteErrors = mutableListOf<String>()

        ApplicationManager.getApplication().invokeLater {
            WriteCommandAction.runWriteCommandAction(project) {
                // Create files
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

                // Delete files
                existingDeletionTargets.forEach { target ->
                    try {
                        target.virtualFile?.delete(this@PasteAndRestoreFilesAction)
                        deleteSuccessCount++
                        logger.info("Deleted file: ${target.relativePath}")
                    } catch (e: Exception) {
                        deleteFailCount++
                        deleteErrors.add("${target.relativePath}: ${e.message}")
                        logger.error("Failed to delete file: ${target.relativePath}", e)
                    }
                }

                // Show result notification
                val resultParts = mutableListOf<String>()
                if (successCount > 0) {
                    val skipNote = if (skipCount > 0) " ($skipCount skipped)" else ""
                    resultParts.add("Created $successCount file(s)$skipNote")
                }
                if (deleteSuccessCount > 0) {
                    resultParts.add("Deleted $deleteSuccessCount file(s)")
                }

                if (resultParts.isNotEmpty()) {
                    CopyFileContentAction.showNotification(
                        "<html><b>${resultParts.joinToString(", ")}</b></html>",
                        NotificationType.INFORMATION,
                        project
                    )
                }

                // Show errors
                val allErrors = errors + deleteErrors
                val totalFailed = failCount + deleteFailCount
                if (allErrors.isNotEmpty()) {
                    val errorMessage = "<html><b>Failed: $totalFailed operation(s):</b><br>" +
                        allErrors.take(5).joinToString("<br>") { it } +
                        (if (allErrors.size > 5) "<br>..." else "") +
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
                currentIsDeleted = ChangeTypeLabel.isDeleted(rawPath)
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
        // Use ChangeTypeLabel enum for consistent label handling
        val stripped = ChangeTypeLabel.stripLabels(path)

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

    /**
     * Find files in project that match [DELETED] entries from clipboard.
     * Only returns targets that exist within the project root.
     */
    private fun findDeletionTargets(
        projectRoot: VirtualFile,
        deletedFiles: List<ParsedFile>
    ): List<DeletionTarget> {
        return deletedFiles.mapNotNull { parsedFile ->
            val relativePath = normalizePathForCurrentPlatform(parsedFile.path.trim())
            if (relativePath.isEmpty()) return@mapNotNull null

            val pathParts = relativePath.split("/")
            var currentDir: VirtualFile? = projectRoot

            for (i in 0 until pathParts.size - 1) {
                currentDir = currentDir?.findChild(pathParts[i])
                if (currentDir == null || !currentDir.isDirectory) break
            }

            val targetFile = currentDir?.findChild(pathParts.last())

            DeletionTarget(
                relativePath = relativePath,
                virtualFile = targetFile,
                exists = targetFile != null && targetFile.exists() && !targetFile.isDirectory
            )
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}