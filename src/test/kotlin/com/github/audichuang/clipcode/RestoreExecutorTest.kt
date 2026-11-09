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
    fun testRestoresUnmatchedAbsolutePathsWithTheirEntireDirectoryTree() {
        val root = Files.createTempDirectory("clipcode-literal-restore")
        try {
            val paths = listOf(
                "D:\\Users\\author\\.m2\\repository\\library.jar!\\com\\example\\Library\$Inner.java" to
                    "D/Users/author/.m2/repository/library.jar!/com/example/Library\$Inner.java",
                "/foreign/checkout/src/New.kt" to "foreign/checkout/src/New.kt",
                "\\\\server\\share\\arbitrary\\New.txt" to "server/share/arbitrary/New.txt"
            )
            val payload = paths.mapIndexed { index, (source, _) -> "// file: $source\ncontent-$index" }
                .joinToString("\n")
            val entries = ClipboardRestoreParser().parse(payload, "// file: \$FILE_PATH")
            val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.systemIndependentPath()))
            val plan = RestorePlanBuilder(resolver).build(entries)

            assertEquals(paths.map { it.second }, plan.createOperations.map { it.relativePath })
            assertTrue(plan.skippedOperations.isEmpty())
            val result = RestoreExecutor(project).execute(plan, overwriteExisting = false, skipExisting = false)
            assertEquals(paths.size, result.createdCount)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            paths.forEachIndexed { index, (_, relative) ->
                assertEquals("content-$index", root.resolve(relative).readText())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

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
                skippedOperations = emptyList(),
                roots = listOf(rootPath)
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
                skippedOperations = emptyList(),
                roots = listOf(rootPath)
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
                    skippedOperations = emptyList(),
                    roots = listOf(rootPath)
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
                skippedOperations = emptyList(),
                roots = listOf(rootPath)
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

    /**
     * The pre-write containment re-check must hold for a DELETE-ONLY plan. It used to
     * derive its root set from the CREATE operations, so a plan with none carried an empty
     * set, the check short-circuited to "allowed", and a symlink appearing between planning
     * and execution let the delete follow it out of the project — the exact race the
     * re-check exists to close.
     */
    fun testDeleteOnlyPlanStillRechecksContainmentBeforeUnlinking() {
        val root = Files.createTempDirectory("clipcode-executor-delete-recheck")
        try {
            val outside = Files.createTempDirectory("clipcode-executor-outside")
            try {
                val victim = outside.resolve("keep.txt")
                victim.writeText("outside original")
                // Planned as an ordinary in-project directory; it becomes a link afterwards.
                val link = root.resolve("link")
                Files.createSymbolicLink(link, outside)

                val result = RestoreExecutor(project).execute(
                    RestorePlan(
                        createOperations = emptyList(),
                        deleteOperations = listOf(
                            RestorePlan.DeleteOperation(
                                relativePath = "link/keep.txt",
                                absolutePath = link.resolve("keep.txt").systemIndependentPath()
                            )
                        ),
                        skippedOperations = emptyList(),
                        roots = listOf(root.systemIndependentPath())
                    ),
                    overwriteExisting = false,
                    skipExisting = false
                )

                assertEquals(0, result.deletedCount)
                assertTrue(result.errors.any { it.contains("unsafe path") }, result.errors.toString())
                assertTrue(victim.exists(), "a file outside the project must survive")
                assertEquals("outside original", victim.readText())
            } finally {
                outside.toFile().deleteRecursively()
            }
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
                    skippedOperations = emptyList(),
                    roots = listOf(missingRoot.systemIndependentPath())
                ),
                overwriteExisting = false,
                skipExisting = false
            )

            assertEquals(0, result.createdCount)
            assertEquals(1, result.errors.size)
            assertTrue(result.errors.single().contains("src/Broken.kt"))
            // Pin WHICH error: a missing root is a create failure, not an escape. Without
            // this the containment re-check could start firing here and the test would go
            // on passing while proving something else.
            assertFalse(result.errors.single().contains("unsafe path"), result.errors.toString())
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
                    skippedOperations = emptyList(),
                    roots = listOf(rootPath)
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

    fun testRestoredContentSurvivesANonUnicodeProjectEncoding() {
        val encodings = com.intellij.openapi.vfs.encoding.EncodingProjectManager.getInstance(project)
        val previous = encodings.defaultCharsetName
        val root = Files.createTempDirectory("clipcode-executor-charset")
        try {
            encodings.setDefaultCharsetName("Big5")
            val target = root.resolve("src/Cjk.kt")
            val content = "hello \u20ac \u3041 \u65e5\u672c\u8a9e"
            val result = RestoreExecutor(project).execute(
                RestorePlan(
                    createOperations = listOf(
                        RestorePlan.CreateOperation(
                            relativePath = "src/Cjk.kt",
                            absolutePath = target.systemIndependentPath(),
                            rootPath = root.systemIndependentPath(),
                            content = content,
                            existed = false
                        )
                    ),
                    deleteOperations = emptyList(),
                    skippedOperations = emptyList(),
                    roots = listOf(root.systemIndependentPath())
                ),
                overwriteExisting = true,
                skipExisting = false,
                indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
            )
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(1, result.createdCount)
            // Big5 cannot represent these, and the encoder substitutes '?' silently.
            assertEquals(content, String(java.nio.file.Files.readAllBytes(target), Charsets.UTF_8))
        } finally {
            encodings.setDefaultCharsetName(previous)
            root.toFile().deleteRecursively()
        }
    }

    fun testOverwritingAnAsciiFileMappedToALegacyCharsetStillWritesUtf8() {
        val root = Files.createTempDirectory("clipcode-executor-overwrite-charset")
        try {
            val target = root.resolve("legacy.txt")
            // Pure ASCII on disk, so Utf8Text.mustNotOverwrite lets the write through — the
            // IDE's charset for that file is what decided the bytes, and windows-1252 wrote
            // "café" as 63 61 66 E9 while reporting a successful overwrite.
            target.writeText("ascii")
            val file = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                .refreshAndFindFileByPath(target.systemIndependentPath())!!
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                file.charset = java.nio.charset.Charset.forName("windows-1252")
            }

            val result = RestoreExecutor(project).execute(
                RestorePlan(
                    createOperations = listOf(
                        RestorePlan.CreateOperation(
                            relativePath = "legacy.txt",
                            absolutePath = target.systemIndependentPath(),
                            rootPath = root.systemIndependentPath(),
                            content = "caf\u00e9",
                            existed = true
                        )
                    ),
                    deleteOperations = emptyList(),
                    skippedOperations = emptyList(),
                    roots = listOf(root.systemIndependentPath())
                ),
                overwriteExisting = true,
                skipExisting = false,
                indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
            )

            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(1, result.overwrittenCount)
            assertEquals(
                "caf\u00e9".toByteArray(Charsets.UTF_8).toList(),
                Files.readAllBytes(target).toList()
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testUserErrorTextMapsOsErrorsToPlainWords() {
        assertEquals("Folder is not writable", userErrorText(java.nio.file.AccessDeniedException("/x")))
        assertEquals("Folder is not writable", userErrorText(java.io.IOException("Permission denied (os error 13)")))
        assertEquals("File or folder not found", userErrorText(java.nio.file.NoSuchFileException("/x")))
        assertEquals("A file or folder already exists at that path", userErrorText(java.nio.file.FileAlreadyExistsException("/x")))
        assertEquals("A file is in the way of its parent folder", userErrorText(java.nio.file.NotDirectoryException("/x")))
        assertEquals("A file is in the way of its parent folder", userErrorText(java.io.IOException("Not a directory (os error 20)")))
        assertEquals("Could not apply the change", userErrorText(IllegalStateException("boom /secret/path")))
    }

    private fun nonTextOp(root: Path, rel: String, bytes: ByteArray, existed: Boolean, identical: Boolean = false) =
        RestorePlan.CreateOperation(
            relativePath = rel,
            absolutePath = root.resolve(rel).systemIndependentPath(),
            rootPath = root.systemIndependentPath(),
            content = "",
            existed = existed,
            nonTextBytes = bytes,
            identical = identical
        )

    private fun planOf(root: Path, vararg ops: RestorePlan.CreateOperation) = RestorePlan(
        createOperations = ops.toList(),
        deleteOperations = emptyList(),
        skippedOperations = emptyList(),
        roots = listOf(root.systemIndependentPath())
    )

    fun testNonTextBytesAreWrittenByteForByteAndUnchangedIsCounted() {
        val root = Files.createTempDirectory("clipcode-nt-exec")
        try {
            // Bytes that are neither UTF-8 nor free of NUL: any charset round trip would corrupt them.
            val bytes = byteArrayOf(0, 0xa4.toByte(), 0xe9.toByte(), 0x0d, 0x0a, 0xff.toByte(), 0)
            Files.write(root.resolve("same.bin"), bytes)
            Files.write(root.resolve("old.bin"), byteArrayOf(9, 9))
            val plan = planOf(
                root,
                nonTextOp(root, "new/dir/n.bin", bytes, existed = false),
                nonTextOp(root, "same.bin", bytes, existed = true, identical = true),
                nonTextOp(root, "old.bin", bytes, existed = true)
            )
            val executor = RestoreExecutor(project)
            // identical targets need no overwrite permission
            assertEquals(listOf("old.bin"), executor.collectExistingCreatePaths(plan))
            val result = executor.execute(plan, overwriteExisting = true, skipExisting = false)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(1, result.createdCount)
            assertEquals(1, result.overwrittenCount)
            assertEquals(1, result.unchangedCount)
            listOf("new/dir/n.bin", "same.bin", "old.bin").forEach {
                assertEquals(bytes.toList(), Files.readAllBytes(root.resolve(it)).toList(), it)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testIdenticalNonTextTargetKeepsItsMtime() {
        val root = Files.createTempDirectory("clipcode-nt-mtime")
        try {
            val bytes = byteArrayOf(0, 1, 2, 0xff.toByte())
            val target = root.resolve("same.bin")
            Files.write(target, bytes)
            val old = java.nio.file.attribute.FileTime.fromMillis(1_000_000_000_000L)
            Files.setLastModifiedTime(target, old)
            val plan = planOf(root, nonTextOp(root, "same.bin", bytes, existed = true, identical = true))
            val result = RestoreExecutor(project).execute(plan, overwriteExisting = true, skipExisting = false)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(1, result.unchangedCount)
            assertEquals(old, Files.getLastModifiedTime(target))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testNonTextOverwriteIsSkippedWithoutPermissionAndNeverTouchesFolders() {
        val root = Files.createTempDirectory("clipcode-nt-exec-skip")
        try {
            val old = byteArrayOf(1, 2, 3)
            Files.write(root.resolve("old.bin"), old)
            root.resolve("folder").createDirectories()
            val bytes = byteArrayOf(0, 7)
            val plan = planOf(
                root,
                nonTextOp(root, "old.bin", bytes, existed = true),
                nonTextOp(root, "folder", bytes, existed = true)
            )
            val result = RestoreExecutor(project).execute(plan, overwriteExisting = false, skipExisting = true)
            assertEquals(2, result.skippedExistingCount)
            assertEquals(old.toList(), Files.readAllBytes(root.resolve("old.bin")).toList())
            assertTrue(Files.isDirectory(root.resolve("folder")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    fun testNonTextOverwriteOfNonUtf8TargetIsNotRefused() {
        val root = Files.createTempDirectory("clipcode-nt-exec-big5")
        try {
            Files.write(root.resolve("big5.txt"), byteArrayOf(0xa4.toByte(), 0xe9.toByte(), 0xa5.toByte(), 0xbb.toByte()))
            val bytes = byteArrayOf(0xa4.toByte(), 0xe9.toByte(), 0x00)
            val result = RestoreExecutor(project).execute(
                planOf(root, nonTextOp(root, "big5.txt", bytes, existed = true)),
                overwriteExisting = true,
                skipExisting = false
            )
            assertEquals(1, result.overwrittenCount)
            assertEquals(bytes.toList(), Files.readAllBytes(root.resolve("big5.txt")).toList())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')
}
