package com.github.audichuang.clipcode

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RestoreExecutorTest : BasePlatformTestCase() {
    fun testExecutesCreateSkipOverwriteAndDeleteOperations() {
        val root = Files.createTempDirectory("clipcode-executor")
        try {
            val existingFile = root.resolve("src/Main.kt")
            val newFile = root.resolve("src/New.kt")
            val deletedFile = root.resolve("src/Old.kt")
            listOf(existingFile, deletedFile).forEach { file ->
                file.parent.createDirectories()
            }
            existingFile.writeText("old content")
            deletedFile.writeText("obsolete")

            val executor = RestoreExecutor(project)
            val rootPath = root.systemIndependentPath()

            val skipPlan = RestorePlan(
                createOperations = listOf(
                    RestorePlan.CreateOperation(
                        relativePath = "src/Main.kt",
                        absolutePath = existingFile.systemIndependentPath(),
                        rootPath = rootPath,
                        content = "new content",
                        existed = true
                    ),
                    RestorePlan.CreateOperation(
                        relativePath = "src/New.kt",
                        absolutePath = newFile.systemIndependentPath(),
                        rootPath = rootPath,
                        content = "created",
                        existed = false
                    )
                ),
                deleteOperations = listOf(
                    RestorePlan.DeleteOperation(
                        relativePath = "src/Old.kt",
                        absolutePath = deletedFile.systemIndependentPath()
                    ),
                    RestorePlan.DeleteOperation(
                        relativePath = "src/Missing.kt",
                        absolutePath = root.resolve("src/Missing.kt").systemIndependentPath()
                    )
                ),
                skippedOperations = emptyList()
            )

            val skipResult = executor.execute(skipPlan, overwriteExisting = false, skipExisting = true)
            assertEquals(1, skipResult.createdCount)
            assertEquals(0, skipResult.overwrittenCount)
            assertEquals(1, skipResult.skippedExistingCount)
            assertEquals(1, skipResult.deletedCount)
            assertTrue(newFile.exists())
            assertEquals("old content", existingFile.readText())
            assertFalse(deletedFile.exists())
            assertTrue(skipResult.errors.isEmpty())

            val overwritePlan = RestorePlan(
                createOperations = listOf(
                    RestorePlan.CreateOperation(
                        relativePath = "src/Main.kt",
                        absolutePath = existingFile.systemIndependentPath(),
                        rootPath = rootPath,
                        content = "updated",
                        existed = true
                    )
                ),
                deleteOperations = emptyList(),
                skippedOperations = emptyList()
            )

            val overwriteResult = executor.execute(overwritePlan, overwriteExisting = true, skipExisting = false)
            assertEquals(0, overwriteResult.createdCount)
            assertEquals(1, overwriteResult.overwrittenCount)
            assertEquals("updated", existingFile.readText())
            assertTrue(overwriteResult.errors.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testExistingFileIsSkippedWhenOverwriteAndSkipAreBothFalse() {
        val root = Files.createTempDirectory("clipcode-executor-default-skip")
        try {
            val existingFile = root.resolve("src/Main.kt")
            existingFile.parent.createDirectories()
            existingFile.writeText("old content")
            val rootPath = root.systemIndependentPath()
            val executor = RestoreExecutor(project)

            val result = executor.execute(
                RestorePlan(
                    createOperations = listOf(
                        RestorePlan.CreateOperation(
                            relativePath = "src/Main.kt",
                            absolutePath = existingFile.systemIndependentPath(),
                            rootPath = rootPath,
                            content = "new content",
                            existed = true
                        )
                    ),
                    deleteOperations = emptyList(),
                    skippedOperations = emptyList()
                ),
                overwriteExisting = false,
                skipExisting = false
            )

            assertEquals(0, result.createdCount)
            assertEquals(0, result.overwrittenCount)
            assertEquals(1, result.skippedExistingCount)
            assertEquals("old content", existingFile.readText())
            assertTrue(result.errors.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testFileCreatedAfterPlanIsSkippedWhenOverwriteWasNotApproved() {
        val root = Files.createTempDirectory("clipcode-executor-race-skip")
        try {
            val targetFile = root.resolve("src/Race.kt")
            targetFile.parent.createDirectories()
            val rootPath = root.systemIndependentPath()
            val executor = RestoreExecutor(project)
            val plan = RestorePlan(
                createOperations = listOf(
                    RestorePlan.CreateOperation(
                        relativePath = "src/Race.kt",
                        absolutePath = targetFile.systemIndependentPath(),
                        rootPath = rootPath,
                        content = "new content",
                        existed = false
                    )
                ),
                deleteOperations = emptyList(),
                skippedOperations = emptyList()
            )

            targetFile.writeText("appeared after planning")

            val result = executor.execute(plan, overwriteExisting = false, skipExisting = false)

            assertEquals(0, result.createdCount)
            assertEquals(0, result.overwrittenCount)
            assertEquals(1, result.skippedExistingCount)
            assertEquals("appeared after planning", targetFile.readText())
            assertTrue(result.errors.isEmpty())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testCollectsCreateErrors() {
        val root = Files.createTempDirectory("clipcode-executor-errors")
        try {
            val missingRoot = root.resolve("missing-root")
            val executor = RestoreExecutor(project)

            val result = executor.execute(
                RestorePlan(
                    createOperations = listOf(
                        RestorePlan.CreateOperation(
                            relativePath = "src/Broken.kt",
                            absolutePath = missingRoot.resolve("src/Broken.kt").systemIndependentPath(),
                            rootPath = missingRoot.systemIndependentPath(),
                            content = "broken",
                            existed = false
                        )
                    ),
                    deleteOperations = emptyList(),
                    skippedOperations = emptyList()
                ),
                overwriteExisting = false,
                skipExisting = false
            )

            assertEquals(0, result.createdCount)
            assertEquals(1, result.errors.size)
            assertTrue(result.errors.single().contains("src/Broken.kt"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testFindFileRefusesToOverwriteDirectory() {
        val root = Files.createTempDirectory("clipcode-executor-directory")
        try {
            val targetDirectory = root.resolve("src/Main.kt")
            targetDirectory.createDirectories()
            val executor = RestoreExecutor(project)
            val rootPath = root.systemIndependentPath()

            val result = executor.execute(
                RestorePlan(
                    createOperations = listOf(
                        RestorePlan.CreateOperation(
                            relativePath = "src/Main.kt",
                            absolutePath = targetDirectory.systemIndependentPath(),
                            rootPath = rootPath,
                            content = "should not be written",
                            existed = false
                        )
                    ),
                    deleteOperations = emptyList(),
                    skippedOperations = emptyList()
                ),
                overwriteExisting = true,
                skipExisting = false
            )

            assertEquals(0, result.createdCount)
            assertEquals(0, result.overwrittenCount)
            assertEquals(1, result.skippedExistingCount)
            assertTrue(result.errors.isEmpty())
            assertTrue(Files.isDirectory(targetDirectory))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')
}
