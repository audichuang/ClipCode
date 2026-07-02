package com.github.audichuang.clipcode

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitClipboardPayloadBuilderTest : BasePlatformTestCase() {

    fun testAppendContentPrefersRevisionContentWhenEntryAlsoHasVirtualFile() {
        val file = myFixture.addFileToProject("src/App.kt", "working tree").virtualFile
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = file.path,
            virtualFile = file,
            contentFromRevision = "revision content"
        )
        val lines = mutableListOf<String>()

        val skipped = invokeAppendContent(lines, entry, maxFileSizeBytes = 500 * 1024)

        assertEquals(0, skipped)
        assertEquals(listOf("revision content"), lines)
    }

    fun testAppendContentUsesVirtualFileWhenNoRevision() {
        val file = myFixture.addFileToProject("src/VfOnly.kt", "vf content").virtualFile
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = file.path,
            virtualFile = file,
            contentFromRevision = null
        )
        val lines = mutableListOf<String>()

        val skipped = invokeAppendContent(lines, entry, maxFileSizeBytes = 500 * 1024)

        assertEquals(0, skipped)
        assertEquals(listOf("vf content"), lines)
    }

    fun testAppendContentEmitsUnableMessageWhenNothingAvailable() {
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/Empty.kt",
            virtualFile = null,
            contentFromRevision = null
        )
        val lines = mutableListOf<String>()

        val skipped = invokeAppendContent(lines, entry, maxFileSizeBytes = 500 * 1024)

        assertEquals(0, skipped)
        assertTrue(lines.single().contains("Unable to read file content"))
    }

    fun testAppendContentSkipsOversizedRevisionContent() {
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/BigRev.kt",
            virtualFile = null,
            contentFromRevision = "x".repeat(2048)
        )
        val lines = mutableListOf<String>()
        val skipped = invokeAppendContent(lines, entry, maxFileSizeBytes = 1024)
        assertEquals(1, skipped)
        assertTrue(lines.single().contains("size exceeds limit"))
    }

    fun testAppendContentSkipsOversizedVirtualFile() {
        val file = myFixture.addFileToProject("src/BigVf.kt", "y".repeat(2048)).virtualFile
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = file.path,
            virtualFile = file,
            contentFromRevision = null
        )
        val lines = mutableListOf<String>()
        val skipped = invokeAppendContent(lines, entry, maxFileSizeBytes = 1024)
        assertEquals(1, skipped)
        assertTrue(lines.single().contains("size exceeds limit"))
    }

    // === helpers ===

    private fun invokeAppendContent(
        lines: MutableList<String>,
        entry: GitContentResolver.ResolvedGitEntry,
        maxFileSizeBytes: Long,
        headerFormat: String = "// file: \$FILE_PATH"
    ): Int {
        val method = GitClipboardPayloadBuilder::class.java.getDeclaredMethod(
            "appendContent",
            MutableList::class.java,
            GitContentResolver.ResolvedGitEntry::class.java,
            Long::class.javaPrimitiveType,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(GitClipboardPayloadBuilder, lines, entry, maxFileSizeBytes, headerFormat) as Int
    }
}
