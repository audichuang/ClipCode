// file: src/main/kotlin/com/github/audichuang/clipcode/ExternalLibraryHandler.kt
package com.github.audichuang.clipcode

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.impl.LoadTextUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiCompiledElement
import com.intellij.psi.PsiManager
import java.io.IOException

/**
 * Handler for processing files from External Libraries.
 * Handles special cases like decompiled .class files, files within JARs/ZIPs, and JRT (Java 9+) modules.
 */
class ExternalLibraryHandler(private val project: Project) {
    private val logger = Logger.getInstance(ExternalLibraryHandler::class.java)
    
    /**
     * Check if a file is from an external library.
     * Uses IntelliJ's ProjectFileIndex for robust detection, plus checks for JAR/ZIP/JRT protocols.
     */
    fun isFromExternalLibrary(file: VirtualFile): Boolean {
        // 1. 使用 IntelliJ 官方 API 檢查是否屬於 Library (最準確)
        if (ProjectFileIndex.getInstance(project).isInLibrary(file)) {
            return true
        }

        // 2. 備用檢查：檢查檔案協議 (Protocol)
        val protocol = file.fileSystem.protocol
        return protocol == "jar" || 
               protocol == "zip" || 
               protocol == "jrt" || // Java 9+ Runtime environment
               file.path.contains("!/")
    }
    
    /**
     * Get a clean path representation for external library files.
     * Automatically converts .class extensions to .java to be LLM-friendly.
     */
    fun getCleanPath(file: VirtualFile): String {
        val protocol = file.fileSystem.protocol
        var cleanPath = file.presentableUrl

        when {
            // 處理 Java 9+ JRT 模組 (例如: java.base/java/lang/String.class)
            protocol == "jrt" -> {
                // JRT 路徑通常像: /modules/java.base/java/lang/String.class
                // 我們只想要: java.base/java/lang/String.class
                val path = file.path
                val modulesPrefix = "/modules/"
                cleanPath = if (path.startsWith(modulesPrefix)) {
                    path.substring(modulesPrefix.length)
                } else {
                    path.trimStart('/')
                }
            }
            // 處理 JAR/ZIP (例如: foo.jar!/com/example/Bar.class)
            file.path.contains("!/") -> {
                val parts = file.presentableUrl.split("!/")
                if (parts.size > 1) {
                    val jarName = parts[0].substringAfterLast('/')
                    cleanPath = "${jarName}!/${parts.drop(1).joinToString("!/")}"
                }
            }
        }

        // 副檔名偽裝：如果是 .class，顯示為 .java，讓 LLM 更容易理解
        if (file.extension?.lowercase() == "class") {
            cleanPath = cleanPath.replaceSuffix(".class", ".java")
        }

        return cleanPath
    }
    
    // Helper extension to strictly replace suffix
    private fun String.replaceSuffix(oldSuffix: String, newSuffix: String): String {
        return if (this.endsWith(oldSuffix)) {
            this.substring(0, this.length - oldSuffix.length) + newSuffix
        } else {
            this
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
     * Get decompiled content from .class files using IntelliJ's PSI system.
     */
    private fun getDecompiledClassContent(file: VirtualFile): String {
        return try {
            // Method 1: The standard IntelliJ PSI way (Most Robust)
            val psiFile = PsiManager.getInstance(project).findFile(file)
            if (psiFile is PsiCompiledElement) {
                // .mirror returns the PsiFile representing the source code (decompiled or from attached sources)
                return psiFile.mirror.text
            }
            
            // Method 2: Fallback to LoadTextUtil
            val text = LoadTextUtil.loadText(file)
            if (text.isNotEmpty()) {
                return text.toString()
            }
            
            return psiFile?.viewProvider?.document?.text ?: ""
            
        } catch (e: Exception) {
            logger.warn("Could not decompile ${file.name}: ${e.message}")
            "// Error: Could not retrieve source code for ${file.name}"
        }
    }
    
    private fun getSourceContent(file: VirtualFile): String {
        return try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: IOException) {
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
    
    private fun getTextContent(file: VirtualFile): String {
        return try {
            LoadTextUtil.loadText(file).toString()
        } catch (e: Exception) {
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
    
    private fun isBinaryFile(file: VirtualFile): Boolean {
        val textExtensions = setOf(
            "java", "kt", "kts", "groovy", "scala", "py", "js", "ts", "tsx", "jsx",
            "xml", "json", "yaml", "yml", "properties", "txt", "md", "html", "css",
            "scss", "sass", "less", "sql", "sh", "bat", "gradle", "pro", "cfg", "conf"
        )
        return file.extension?.lowercase() !in textExtensions
    }
    
    fun shouldProcessFile(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        
        // Skip very large files (over 10MB)
        if (file.length > 10 * 1024 * 1024) {
            logger.info("Skipping large external library file: ${file.name} (${file.length} bytes)")
            return false
        }
        
        val skipExtensions = setOf("so", "dll", "dylib", "exe", "bin", "dat", "db")
        if (file.extension?.lowercase() in skipExtensions) {
            return false
        }
        
        return true
    }
}