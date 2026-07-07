package com.github.audichuang.clipcode

import com.intellij.openapi.progress.EmptyProgressIndicator
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

    fun testBuildEmitsClipcodeRootMetadataLineForSingleRoot() {
        val payload = buildPayload(rootPaths = listOf("/work/myrepo"))

        assertTrue(payload.text.startsWith("// clipcode-root: myrepo\n"))
    }

    fun testBuildOmitsClipcodeRootMetadataLineForMultipleRoots() {
        val payload = buildPayload(rootPaths = listOf("/work/myrepo", "/work/other"))

        assertTrue(!payload.text.contains("clipcode-root"))
    }

    fun testBuildSuppressesMetadataLineWhenItWouldParseAsHeader() {
        // A degenerate headerFormat would parse the metadata line as a file header on
        // restore — the builder must drop the line rather than emit a phantom entry.
        val payload = withHeaderFormat("\$FILE_PATH") {
            buildPayload(rootPaths = listOf("/work/myrepo"))
        }

        assertTrue(!payload.text.contains("clipcode-root"))
    }

    fun testDeletedMarkerLineIsEscapedUnderPermissiveHeaderFormat() {
        // "// This file has been deleted in this change" parses as a header under
        // "// $FILE_PATH" — it must be escaped like real content (VS Code does too).
        val payload = withHeaderFormat("// \$FILE_PATH") {
            buildPayload(rootPaths = listOf("/work/myrepo"))
        }

        assertTrue(
            payload.text.contains(
                ClipboardRestoreParser.ESCAPE_MARKER + "// This file has been deleted in this change"
            )
        )
    }

    fun testSkipLineIsEscapedUnderPermissiveHeaderFormat() {
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/BigRev.kt",
            virtualFile = null,
            contentFromRevision = "x".repeat(2048)
        )
        val lines = mutableListOf<String>()

        invokeAppendContent(lines, entry, maxFileSizeBytes = 1024, headerFormat = "// \$FILE_PATH")

        assertTrue(lines.single().startsWith(ClipboardRestoreParser.ESCAPE_MARKER + "// File skipped:"))
    }

    // === helpers ===

    private fun buildPayload(rootPaths: List<String>): GitClipboardPayloadBuilder.Payload {
        val resolver = ClipboardPathResolver.fromRootPaths(rootPaths)
        return GitClipboardPayloadBuilder.build(
            contentEntries = emptyList(),
            deletedMarkerEntries = listOf(
                GitContentResolver.ResolvedGitEntry(
                    changeType = ChangeTypeLabel.DELETED,
                    filePath = "${rootPaths.first()}/src/Old.kt",
                    virtualFile = null,
                    contentFromRevision = null
                )
            ),
            pathResolver = resolver,
            settings = CopyFileContentSettings.getInstance(project),
            indicator = EmptyProgressIndicator()
        )
    }

    private fun <T> withHeaderFormat(headerFormat: String, block: () -> T): T {
        val settings = CopyFileContentSettings.getInstance(project)!!
        val previous = settings.state.headerFormat
        settings.state.headerFormat = headerFormat
        return try {
            block()
        } finally {
            settings.state.headerFormat = previous
        }
    }

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
