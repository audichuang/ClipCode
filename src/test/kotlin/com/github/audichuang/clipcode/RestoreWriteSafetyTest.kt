package com.github.audichuang.clipcode

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * X12 (never write through a symlink at the target, keep permission bits) and X13 (a read-only
 * target follows the overwrite option). JUnit4 runner so org.junit.Assume reports a skip: Windows
 * may lack symlink privilege, and has no POSIX mode bits. Every fixture is created by trying the
 * real operation; on POSIX it must succeed.
 */
@RunWith(JUnit4::class)
class RestoreWriteSafetyTest : BasePlatformTestCase() {
    private val posix = !System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private fun symlinkOrSkip(link: Path, target: Path) {
        try {
            Files.createSymbolicLink(link, target)
        } catch (e: Exception) {
            if (posix) throw e
            assumeTrue("cannot create symlinks here: ${e.javaClass.simpleName}", false)
        }
    }

    private fun readOnlyOrSkip(file: Path) {
        assertTrue(file.toFile().setReadOnly() || !posix)
        assumeTrue("read-only attribute not supported", !file.toFile().canWrite())
    }

    private fun nonTextEntry(path: String, bytes: ByteArray) = ClipboardRestoreParser.ParsedClipboardEntry(
        path = path,
        content = NonText.placeholderLine(bytes.size.toLong(), NonText.sha256Hex(bytes)) + "\n" + NonText.encodeWrapped(bytes)
    )

    private fun run(root: Path, entries: List<ClipboardRestoreParser.ParsedClipboardEntry>, overwrite: Boolean): Pair<RestorePlan, RestoreExecutor.ExecutionResult> {
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        val rootPath = root.toString().replace('\\', '/')
        val plan = RestorePlanBuilder(ClipboardPathResolver.fromRootPaths(listOf(rootPath), rootPath)).build(entries)
        val result = RestoreExecutor(project).execute(plan, overwriteExisting = overwrite, skipExisting = !overwrite)
        return plan to result
    }

    private val newBytes = byteArrayOf(9, 0, 8, 1)

    @Test
    fun testNonTextOverwriteNeverWritesThroughSymlinkToAnotherFileInRoot() {
        val root = Files.createTempDirectory("clipcode-x12-in").toRealPath()
        try {
            val real = root.resolve("real.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            symlinkOrSkip(root.resolve("a.png"), real)
            run(root, listOf(nonTextEntry("a.png", newBytes)), overwrite = true)
            assertEquals(listOf<Byte>(1, 2, 3), real.readBytes().toList(), "the link's target must keep its bytes")
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testTextOverwriteNeverWritesThroughSymlinkToAnotherFileInRoot() {
        val root = Files.createTempDirectory("clipcode-x12-text").toRealPath()
        try {
            val real = root.resolve("real.txt").also { it.writeText("original") }
            symlinkOrSkip(root.resolve("a.txt"), real)
            run(root, listOf(ClipboardRestoreParser.ParsedClipboardEntry("a.txt", "new")), overwrite = true)
            assertEquals("original", real.readText())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testOverwriteNeverWritesThroughSymlinkToAFileOutsideTheRoot() {
        val root = Files.createTempDirectory("clipcode-x12-root").toRealPath()
        val outside = Files.createTempDirectory("clipcode-x12-out").toRealPath()
        try {
            val victim = outside.resolve("victim.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            symlinkOrSkip(root.resolve("a.png"), victim)
            run(root, listOf(nonTextEntry("a.png", newBytes)), overwrite = true)
            assertEquals(listOf<Byte>(1, 2, 3), victim.readBytes().toList())
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun testOverwritingARegularFileKeepsItsPermissionBits() {
        assumeTrue("POSIX permission bits only", posix)
        val root = Files.createTempDirectory("clipcode-x12-mode").toRealPath()
        try {
            val file = root.resolve("run.sh").also { it.writeText("old") }
            val mode = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ)
            Files.setPosixFilePermissions(file, mode)
            val bin = root.resolve("tool.bin").also { it.writeBytes(byteArrayOf(1)) }
            Files.setPosixFilePermissions(bin, mode)
            run(root, listOf(ClipboardRestoreParser.ParsedClipboardEntry("run.sh", "new"), nonTextEntry("tool.bin", newBytes)), overwrite = true)
            assertEquals("new", file.readText())
            assertEquals(mode, Files.getPosixFilePermissions(file))
            assertEquals(newBytes.toList(), bin.readBytes().toList())
            assertEquals(mode, Files.getPosixFilePermissions(bin))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testReadOnlyTargetIsOverwrittenWithConsentAndKeepsItsMode() {
        val root = Files.createTempDirectory("clipcode-x13-yes").toRealPath()
        try {
            val text = root.resolve("t.txt").also { it.writeText("old") }
            val bin = root.resolve("b.bin").also { it.writeBytes(byteArrayOf(1)) }
            readOnlyOrSkip(text)
            readOnlyOrSkip(bin)
            val (_, result) = run(root, listOf(ClipboardRestoreParser.ParsedClipboardEntry("t.txt", "new"), nonTextEntry("b.bin", newBytes)), overwrite = true)
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(2, result.overwrittenCount)
            assertEquals("new", text.readText())
            assertEquals(newBytes.toList(), bin.readBytes().toList())
            assertFalse(text.toFile().canWrite(), "the read-only mode is restored")
            assertFalse(bin.toFile().canWrite(), "the read-only mode is restored")
        } finally {
            root.toFile().walkTopDown().forEach { it.setWritable(true) }
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testReadOnlyTargetIsSkippedWithoutConsent() {
        val root = Files.createTempDirectory("clipcode-x13-no").toRealPath()
        try {
            val text = root.resolve("t.txt").also { it.writeText("old") }
            val bin = root.resolve("b.bin").also { it.writeBytes(byteArrayOf(1)) }
            readOnlyOrSkip(text)
            readOnlyOrSkip(bin)
            val (_, result) = run(root, listOf(ClipboardRestoreParser.ParsedClipboardEntry("t.txt", "new"), nonTextEntry("b.bin", newBytes)), overwrite = false)
            assertEquals(2, result.skippedExistingCount)
            assertEquals("old", text.readText())
            assertEquals(listOf<Byte>(1), bin.readBytes().toList())
        } finally {
            root.toFile().walkTopDown().forEach { it.setWritable(true) }
            root.toFile().deleteRecursively()
        }
    }
}
