package com.github.audichuang.clipcode

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile

class RestoreExecutor(
    private val project: Project
) {
    data class ExecutionResult(
        val createdCount: Int,
        val overwrittenCount: Int,
        val skippedExistingCount: Int,
        val deletedCount: Int,
        val errors: List<String>
    )

    fun collectExistingCreatePaths(plan: RestorePlan): List<String> =
        plan.createOperations.filter { it.existed }.map { it.relativePath }

    fun execute(
        plan: RestorePlan,
        overwriteExisting: Boolean,
        skipExisting: Boolean
    ): ExecutionResult {
        var createdCount = 0
        var overwrittenCount = 0
        var skippedExistingCount = 0
        var deletedCount = 0
        val errors = mutableListOf<String>()

        WriteCommandAction.runWriteCommandAction(project) {
            plan.createOperations.forEach { operation ->
                try {
                    val existingFile = findFile(operation.absolutePath)
                    when {
                        existingFile != null && existingFile.isDirectory -> skippedExistingCount++
                        existingFile != null && skipExisting -> skippedExistingCount++
                        existingFile != null && overwriteExisting -> {
                            VfsUtil.saveText(existingFile, operation.content)
                            overwrittenCount++
                        }

                        existingFile != null -> skippedExistingCount++
                        else -> {
                            val createdFile = createFile(operation.rootPath, operation.relativePath)
                            VfsUtil.saveText(createdFile, operation.content)
                            createdCount++
                        }
                    }
                } catch (e: Exception) {
                    errors.add("${operation.relativePath}: ${e.message}")
                }
            }

            plan.deleteOperations.forEach { operation ->
                try {
                    val targetFile = findFile(operation.absolutePath)
                    if (targetFile != null && targetFile.exists() && !targetFile.isDirectory) {
                        targetFile.delete(this@RestoreExecutor)
                        deletedCount++
                    }
                } catch (e: Exception) {
                    errors.add("${operation.relativePath}: ${e.message}")
                }
            }
        }

        return ExecutionResult(
            createdCount = createdCount,
            overwrittenCount = overwrittenCount,
            skippedExistingCount = skippedExistingCount,
            deletedCount = deletedCount,
            errors = errors
        )
    }

    private fun createFile(rootPath: String, relativePath: String): VirtualFile {
        val rootDir = findFile(rootPath)
            ?: throw IllegalStateException("Unable to resolve root directory: $rootPath")
        if (!rootDir.isDirectory) {
            throw IllegalStateException("Root path is not a directory: $rootPath")
        }

        val pathParts = relativePath.split("/")
        val fileName = pathParts.last()
        val directoryPath = pathParts.dropLast(1)

        var currentDir = rootDir
        directoryPath.forEach { dirName ->
            val existingDir = currentDir.findChild(dirName)
            currentDir = if (existingDir != null && existingDir.isDirectory) {
                existingDir
            } else {
                currentDir.createChildDirectory(this, dirName)
            }
        }

        return currentDir.findChild(fileName) ?: currentDir.createChildData(this, fileName)
    }

    private fun findFile(path: String): VirtualFile? =
        LocalFileSystem.getInstance().findFileByPath(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
}
