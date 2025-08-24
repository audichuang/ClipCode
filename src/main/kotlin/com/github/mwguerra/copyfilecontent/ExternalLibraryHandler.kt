package com.github.mwguerra.copyfilecontent

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiCompiledElement
import java.io.IOException

/**
 * Handler for processing files from External Libraries.
 * Handles special cases like decompiled .class files and files within JARs/ZIPs.
 */
class ExternalLibraryHandler(private val project: Project) {
    private val logger = Logger.getInstance(ExternalLibraryHandler::class.java)
    
    /**
     * Check if a file is from an external library (JAR, ZIP, etc.)
     */
    fun isFromExternalLibrary(file: VirtualFile): Boolean {
        return file.presentableUrl.contains(".jar!") || 
               file.presentableUrl.contains(".zip!") ||
               file.path.contains("!/") ||
               file.fileSystem.protocol == "jar"
    }
    
    /**
     * Get a clean path representation for external library files
     */
    fun getCleanPath(file: VirtualFile): String {
        return when {
            file.presentableUrl.contains("!/") -> {
                // For JAR/ZIP entries, show a cleaner path after the archive separator
                val parts = file.presentableUrl.split("!/")
                if (parts.size > 1) {
                    // Include JAR name and internal path
                    val jarName = parts[0].substringAfterLast('/')
                    "${jarName}!/${parts.drop(1).joinToString("!/")}"
                } else {
                    file.presentableUrl
                }
            }
            else -> file.presentableUrl
        }
    }
    
    /**
     * Read content from external library files, including decompilation when necessary
     */
    fun readContent(file: VirtualFile): String? {
        return try {
            when {
                // Handle .class files - get decompiled content
                file.extension == "class" -> {
                    getDecompiledClassContent(file)
                }
                // Handle source files in JARs
                file.extension in listOf("java", "kt", "groovy", "scala") -> {
                    getSourceContent(file)
                }
                // Handle other text files
                !isBinaryFile(file) -> {
                    getTextContent(file)
                }
                else -> {
                    logger.info("Skipping binary file from external library: ${file.name}")
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to read external library file ${file.presentableUrl}: ${e.message}")
            null
        }
    }
    
    /**
     * Get decompiled content from .class files
     */
    private fun getDecompiledClassContent(file: VirtualFile): String {
        return try {
            // Method 1: Try using LoadTextUtil which IntelliJ uses for displaying decompiled content
            val text = LoadTextUtil.loadText(file)
            if (text.isNotEmpty()) {
                return text.toString()
            }
            
            // Method 2: Try through PSI if LoadTextUtil fails
            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile is PsiCompiledElement) {
                // Get the decompiled text from the PSI element
                val decompiledText = psiFile.text ?: ""
                if (decompiledText.isNotEmpty()) {
                    return decompiledText
                }
            }
            
            // Method 3: Try to get the mirror file (decompiled source)
            psiFile?.viewProvider?.document?.text ?: ""
            
        } catch (e: Exception) {
            logger.warn("Could not decompile ${file.name}: ${e.message}")
            ""
        }
    }
    
    /**
     * Get content from source files in JARs
     */
    private fun getSourceContent(file: VirtualFile): String {
        return try {
            // Try direct reading first
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: IOException) {
            // Fallback to input stream
            try {
                file.inputStream?.use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } ?: ""
            } catch (e2: Exception) {
                logger.warn("Could not read source file ${file.name}: ${e2.message}")
                ""
            }
        }
    }
    
    /**
     * Get content from text files in external libraries
     */
    private fun getTextContent(file: VirtualFile): String {
        return try {
            // Use LoadTextUtil for consistent text loading
            LoadTextUtil.loadText(file).toString()
        } catch (e: Exception) {
            // Fallback to direct reading
            try {
                file.inputStream?.use { inputStream ->
                    inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } ?: ""
            } catch (e2: Exception) {
                logger.warn("Could not read text file ${file.name}: ${e2.message}")
                ""
            }
        }
    }
    
    /**
     * Check if file should be considered binary (excluding special cases)
     */
    private fun isBinaryFile(file: VirtualFile): Boolean {
        val textExtensions = setOf(
            "java", "kt", "kts", "groovy", "scala", "py", "js", "ts", "tsx", "jsx",
            "xml", "json", "yaml", "yml", "properties", "txt", "md", "html", "css",
            "scss", "sass", "less", "sql", "sh", "bat", "gradle", "pro", "cfg", "conf"
        )
        
        return file.extension?.lowercase() !in textExtensions
    }
    
    /**
     * Check if external library file should be processed
     * This can be used to filter out files we definitely can't or shouldn't read
     */
    fun shouldProcessFile(file: VirtualFile): Boolean {
        // Skip directories
        if (file.isDirectory) return false
        
        // Skip very large files (over 10MB)
        if (file.length > 10 * 1024 * 1024) {
            logger.info("Skipping large external library file: ${file.name} (${file.length} bytes)")
            return false
        }
        
        // Skip certain file types that we know we can't handle
        val skipExtensions = setOf("so", "dll", "dylib", "exe", "bin", "dat", "db")
        if (file.extension?.lowercase() in skipExtensions) {
            return false
        }
        
        return true
    }
}