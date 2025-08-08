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
    
    data class ParsedFile(
        val path: String,
        val content: String
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
        
        // Show confirmation dialog with file list
        val fileList = parsedFiles.joinToString("\n") { "• ${it.path}" }
        val message = "Found ${parsedFiles.size} file(s) to restore:\n\n$fileList\n\nDo you want to create these files?"
        
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
                parsedFiles.forEach { parsedFile ->
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
        val currentContent = StringBuilder()
        
        for (line in lines) {
            val match = regex.find(line)
            if (match != null && match.groups.size > 1) {
                // Found a file header
                if (currentFilePath != null && currentContent.isNotEmpty()) {
                    // Save previous file
                    files.add(ParsedFile(currentFilePath, currentContent.toString().trim()))
                }
                // Start new file
                currentFilePath = match.groups[1]?.value
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
            files.add(ParsedFile(currentFilePath, currentContent.toString().trim()))
        }
        
        return files
    }
    
    private fun createFile(project: Project, projectRoot: VirtualFile, parsedFile: ParsedFile) {
        val relativePath = parsedFile.path.trim()
        
        // Split path into directory and filename
        val pathParts = relativePath.split("/")
        val fileName = pathParts.last()
        val directoryPath = pathParts.dropLast(1).joinToString("/")
        
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
    
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null
    }
}