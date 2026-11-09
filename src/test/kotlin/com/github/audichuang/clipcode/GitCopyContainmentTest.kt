package com.github.audichuang.clipcode

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * X7 for the Git copy builder (R1): a working-tree entry is read from disk, so a link whose real target lies
 * outside every copy root must not carry that target's bytes, whether the entry itself is the link
 * (`CLAUDE.md -> ~/.agents/secret`) or it is reached through a linked folder (`vendor -> ~/private`). The
 * builder is where a selection lands as soon as it mixes a staged, revision or deleted entry in, in Include
 * and in Exclude. Real disk: temp:// has no symlinks.
 */
class GitCopyContainmentTest : BasePlatformTestCase() {
    private val posix = java.io.File.separatorChar == '/'

    override fun setUp() {
        super.setUp()
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.headerFormat = "// file: \$FILE_PATH"
        state.preText = ""
        state.postText = ""
        state.addExtraLineBetweenFiles = false
        state.setMaxFileCount = false
        state.showCopyNotification = false
        state.useFilters = false
        state.maxFileSizeKB = 500
    }

    private fun projectDiskDir(): Path =
        Path.of(project.basePath!!, name).also { it.toFile().deleteRecursively() }.also { Files.createDirectories(it) }

    private fun vfs(root: Path, relative: String): VirtualFile {
        val dir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")
        VfsUtil.markDirtyAndRefresh(false, true, true, dir)
        return dir.findFileByRelativePath(relative) ?: error("no vfs for $relative")
    }

    fun testWorkingTreeLinkOutOfEveryRootIsNotReadByTheGitBuilder() {
        val outside = Files.createTempDirectory("clipcode-git-outside")
        // VFS refreshes through the links into this directory; the test framework only lets it read there
        // when allowed. The builder itself never reads it.
        com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess.allowRootAccess(testRootDisposable, outside.toRealPath().toString())
        val root = projectDiskDir()
        try {
            Files.writeString(outside.resolve("secret.txt"), "OUTSIDE-SECRET")
            Files.write(outside.resolve("secret.png"), ByteArray(32) { i -> if (i == 1) 0 else (i * 3).toByte() })
            Files.createDirectories(outside.resolve("private"))
            Files.writeString(outside.resolve("private").resolve("secrets.env"), "VENDOR-SECRET")
            Files.writeString(root.resolve("ok.txt"), "FINE-CONTENT")
            val linked = runCatching {
                Files.createSymbolicLink(root.resolve("CLAUDE.md"), outside.resolve("secret.txt"))
                Files.createSymbolicLink(root.resolve("logo.png"), outside.resolve("secret.png"))
                Files.createSymbolicLink(root.resolve("vendor"), outside.resolve("private"))
                Files.createSymbolicLink(root.resolve("alias.txt"), root.resolve("ok.txt"))
            }.isSuccess
            if (posix) assertTrue(linked, "test setup: the symlinks must exist on POSIX")
            assumeTrue("Windows refuses to create a symlink without the privilege", linked)

            val working = listOf("CLAUDE.md", "logo.png", "vendor/secrets.env", "alias.txt").map { rel ->
                val vf = vfs(root, rel)
                GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, vf.path, vf, null)
            }
            val rootPath = (LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root) ?: error("no vfs dir")).path
            val staged = GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.NEW, "$rootPath/staged.txt", null, "STAGED-BODY")
            val resolver = ClipboardPathResolver.fromRootPaths(listOf(rootPath))
            for (include in listOf(true, false)) {
                val p = GitClipboardPayloadBuilder.build(
                    working + staged, emptyList(), resolver, CopyFileContentSettings.getInstance(project),
                    EmptyProgressIndicator(), include
                )
                assertFalse(p.text.contains("OUTSIDE-SECRET"), "$include: ${p.text}")
                assertFalse(p.text.contains("VENDOR-SECRET"), "$include: ${p.text}")
                assertTrue(p.nonTextCarried.isEmpty(), "$include: the outside PNG must not be carried")
                assertContains(p.text, "// file: [MODIFIED] alias.txt\nFINE-CONTENT")
                assertContains(p.text, "STAGED-BODY")
                assertEquals(3, p.skipNotes.count(SkipCategory.UNREADABLE), "$include: ${p.noticeHtml()}")
                assertEquals(
                    setOf("CLAUDE.md", "logo.png", "vendor/secrets.env"),
                    p.skipNotes.names(SkipCategory.UNREADABLE).toSet()
                )
            }
        } finally {
            root.toFile().deleteRecursively()
            outside.toFile().deleteRecursively()
        }
    }
}
