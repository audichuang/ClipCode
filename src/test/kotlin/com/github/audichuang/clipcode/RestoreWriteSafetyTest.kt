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

    private fun run(
        root: Path,
        entries: List<ClipboardRestoreParser.ParsedClipboardEntry>,
        overwrite: Boolean,
        executor: RestoreExecutor = RestoreExecutor(project)
    ): Pair<RestorePlan, RestoreExecutor.ExecutionResult> {
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, root.toString())
        val rootPath = root.toString().replace('\\', '/')
        val plan = RestorePlanBuilder(ClipboardPathResolver.fromRootPaths(listOf(rootPath), rootPath)).build(entries)
        val result = executor.execute(plan, overwriteExisting = overwrite, skipExisting = !overwrite)
        return plan to result
    }

    private fun assertReplacedLink(link: Path, expected: ByteArray) {
        assertFalse(Files.isSymbolicLink(link), "the link itself is replaced")
        assertTrue(Files.isRegularFile(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertEquals(expected.toList(), link.readBytes().toList())
    }

    private fun assertNoTempLeft(dir: Path) {
        val names = dir.toFile().list()!!.toList()
        assertTrue(names.none { it.startsWith(".clipcode-write.") }, names.toString())
    }

    private val newBytes = byteArrayOf(9, 0, 8, 1)

    @Test
    fun testNonTextOverwriteNeverWritesThroughSymlinkToAnotherFileInRoot() {
        val root = Files.createTempDirectory("clipcode-x12-in").toRealPath()
        try {
            val real = root.resolve("real.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val link = root.resolve("a.png")
            symlinkOrSkip(link, real)
            val (_, result) = run(root, listOf(nonTextEntry("a.png", newBytes)), overwrite = true)
            assertEquals(listOf<Byte>(1, 2, 3), real.readBytes().toList(), "the link's target must keep its bytes")
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(1, result.overwrittenCount)
            assertEquals(0, result.createdCount)
            assertReplacedLink(link, newBytes)
            assertNoTempLeft(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testTextOverwriteNeverWritesThroughSymlinkToAnotherFileInRoot() {
        val root = Files.createTempDirectory("clipcode-x12-text").toRealPath()
        try {
            val real = root.resolve("real.txt").also { it.writeText("original") }
            val link = root.resolve("a.txt")
            symlinkOrSkip(link, real)
            val (_, result) = run(root, listOf(ClipboardRestoreParser.ParsedClipboardEntry("a.txt", "new\r\nline")), overwrite = true)
            assertEquals("original", real.readText())
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(1, result.overwrittenCount)
            assertEquals(0, result.createdCount)
            assertReplacedLink(link, "new\r\nline".toByteArray(Charsets.UTF_8))
            assertNoTempLeft(root)
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

    @Test
    fun testFailedWriteOverASymlinkLeavesTheLinkInPlace() {
        val root = Files.createTempDirectory("clipcode-r3-fail").toRealPath()
        try {
            val real = root.resolve("real.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val realText = root.resolve("real.txt").also { it.writeText("original") }
            val bin = root.resolve("a.png")
            val text = root.resolve("a.txt")
            symlinkOrSkip(bin, real)
            symlinkOrSkip(text, realText)
            val failing = RestoreExecutor(project) { throw java.io.IOException("No space left on device") }
            val (_, result) = run(
                root,
                listOf(nonTextEntry("a.png", newBytes), ClipboardRestoreParser.ParsedClipboardEntry("a.txt", "new")),
                overwrite = true,
                executor = failing
            )
            assertEquals(2, result.errors.size, result.errors.toString())
            assertEquals(0, result.overwrittenCount)
            assertEquals(0, result.createdCount)
            assertTrue(Files.isSymbolicLink(bin), "a failed write must leave the link")
            assertTrue(Files.isSymbolicLink(text), "a failed write must leave the link")
            assertEquals(real, Files.readSymbolicLink(bin))
            assertEquals(realText, Files.readSymbolicLink(text))
            assertEquals(listOf<Byte>(1, 2, 3), real.readBytes().toList())
            assertEquals("original", realText.readText())
            assertNoTempLeft(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testSymlinkInAnUnwritableFolderIsLeftInPlace() {
        assumeTrue("POSIX folder permissions only", posix)
        val root = Files.createTempDirectory("clipcode-r3-ro").toRealPath()
        val dir = root.resolve("d").also { Files.createDirectory(it) }
        try {
            val real = root.resolve("real.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            val link = dir.resolve("a.png")
            symlinkOrSkip(link, real)
            Files.setPosixFilePermissions(dir, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
            assumeTrue("running as root: folder permissions are not enforced", !Files.isWritable(dir))
            val (_, result) = run(root, listOf(nonTextEntry("d/a.png", newBytes)), overwrite = true)
            assertEquals(1, result.errors.size, result.errors.toString())
            assertTrue(Files.isSymbolicLink(link))
            assertEquals(real, Files.readSymbolicLink(link))
            assertEquals(listOf<Byte>(1, 2, 3), real.readBytes().toList())
        } finally {
            dir.toFile().setWritable(true)
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun testUndoPutsTheReplacedSymlinkBackAndRedoReplacesItAgain() {
        val root = Files.createTempDirectory("clipcode-r4-undo").toRealPath()
        try {
            val real = root.resolve("real.bin").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            root.resolve("real.txt").writeText("original")
            val bin = root.resolve("a.png")
            val text = root.resolve("a.txt")
            symlinkOrSkip(bin, real)
            // A relative link must come back relative, exactly as it was written.
            symlinkOrSkip(text, Path.of("real.txt"))
            val (_, result) = run(
                root,
                listOf(nonTextEntry("a.png", newBytes), ClipboardRestoreParser.ParsedClipboardEntry("a.txt", "new")),
                overwrite = true
            )
            assertTrue(result.errors.isEmpty(), result.errors.toString())
            assertEquals(2, result.overwrittenCount)
            val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
            assertTrue(undo.isUndoAvailable(null), "the replacement must be undoable")
            undo.undo(null)
            assertTrue(Files.isSymbolicLink(bin), "undo must bring the link back, not a regular file")
            assertTrue(Files.isSymbolicLink(text), "undo must bring the link back, not a regular file")
            assertEquals(real, Files.readSymbolicLink(bin))
            assertEquals(Path.of("real.txt"), Files.readSymbolicLink(text))
            assertEquals(listOf<Byte>(1, 2, 3), real.readBytes().toList())
            assertEquals("original", root.resolve("real.txt").readText())
            undo.redo(null)
            assertReplacedLink(bin, newBytes)
            assertReplacedLink(text, "new".toByteArray(Charsets.UTF_8))
            assertEquals(listOf<Byte>(1, 2, 3), real.readBytes().toList())
            assertEquals("original", root.resolve("real.txt").readText())
            assertNoTempLeft(root)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
