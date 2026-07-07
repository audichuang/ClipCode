package com.github.audichuang.clipcode

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitClipboardPayloadBuilderTest : BasePlatformTestCase() {

    // === content resolution: revision vs working-tree, size skip, read failure ===

    fun testPrefersRevisionContentWhenEntryAlsoHasVirtualFile() {
        val file = myFixture.addFileToProject("src/App.kt", "working tree").virtualFile
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = file.path,
            virtualFile = file,
            contentFromRevision = "revision content"
        )

        val text = buildText(listOf(entry))

        assertContainsBody(text, "revision content")
        assertFalse(text.contains("working tree"), "revision content must win over the working tree")
    }

    fun testUsesVirtualFileWhenNoRevision() {
        val file = myFixture.addFileToProject("src/VfOnly.kt", "vf content").virtualFile
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = file.path,
            virtualFile = file,
            contentFromRevision = null
        )

        assertContainsBody(buildText(listOf(entry)), "vf content")
    }

    fun testEmitsUnableMessageWhenNothingAvailable() {
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/Empty.kt",
            virtualFile = null,
            contentFromRevision = null
        )

        assertContainsBody(buildText(listOf(entry)), "// Unable to read file content")
    }

    fun testSkipsOversizedRevisionContent() {
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = "src/BigRev.kt",
            virtualFile = null,
            contentFromRevision = "x".repeat(2048)
        )

        assertContainsBody(buildText(listOf(entry), maxFileSizeKB = 1), "// File skipped: size exceeds limit (2048 bytes)")
    }

    fun testSkipsOversizedVirtualFile() {
        val file = myFixture.addFileToProject("src/BigVf.kt", "y".repeat(2048)).virtualFile
        val entry = GitContentResolver.ResolvedGitEntry(
            changeType = ChangeTypeLabel.MODIFIED,
            filePath = file.path,
            virtualFile = file,
            contentFromRevision = null
        )

        assertTrue(buildText(listOf(entry), maxFileSizeKB = 1).contains("// File skipped: size exceeds limit"))
    }

    // === clipcode-root metadata line ===

    fun testEmitsClipcodeRootMetadataLineForSingleRoot() {
        val payload = buildDeletedMarker(rootPaths = listOf("/work/myrepo"))

        assertTrue(payload.text.startsWith("// clipcode-root: myrepo\n"))
    }

    fun testOmitsClipcodeRootMetadataLineForMultipleRoots() {
        assertFalse(buildDeletedMarker(rootPaths = listOf("/work/myrepo", "/work/other")).text.contains("clipcode-root"))
    }

    fun testSuppressesMetadataLineWhenItWouldParseAsHeader() {
        // A degenerate headerFormat would parse the metadata line as a file header on
        // restore — the builder must drop the line rather than emit a phantom entry.
        val payload = buildDeletedMarker(rootPaths = listOf("/work/myrepo"), headerFormat = "\$FILE_PATH")

        assertFalse(payload.text.contains("clipcode-root"))
    }

    // === skip / marker lines escaped under a permissive header format ===

    fun testDeletedMarkerLineIsEscapedUnderPermissiveHeaderFormat() {
        // "// This file has been deleted in this change" parses as a header under
        // "// $FILE_PATH" — it must be escaped like real content (VS Code does too).
        val payload = buildDeletedMarker(rootPaths = listOf("/work/myrepo"), headerFormat = "// \$FILE_PATH")

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

        val text = buildText(listOf(entry), headerFormat = "// \$FILE_PATH", maxFileSizeKB = 1)

        assertTrue(text.contains(ClipboardRestoreParser.ESCAPE_MARKER + "// File skipped:"))
    }

    fun testDeletedMarkerSummaryForSingleEntry() {
        assertEquals("1 deleted file marker copied.", buildDeletedMarker(rootPaths = listOf("/work/myrepo")).summary)
    }

    // === helpers ===

    private fun buildText(
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        headerFormat: String = "// file: \$FILE_PATH",
        maxFileSizeKB: Int = 500
    ): String = withSettings(headerFormat, maxFileSizeKB) {
        GitClipboardPayloadBuilder.build(
            contentEntries = contentEntries,
            deletedMarkerEntries = emptyList(),
            // multi-root → no clipcode-root metadata line, so body assertions stay clean
            pathResolver = ClipboardPathResolver.fromRootPaths(listOf("/work/repo-a", "/work/repo-b")),
            settings = CopyFileContentSettings.getInstance(project),
            indicator = EmptyProgressIndicator()
        ).text
    }

    private fun buildDeletedMarker(
        rootPaths: List<String>,
        headerFormat: String = "// file: \$FILE_PATH"
    ): GitClipboardPayloadBuilder.Payload = withSettings(headerFormat, maxFileSizeKB = 500) {
        GitClipboardPayloadBuilder.build(
            contentEntries = emptyList(),
            deletedMarkerEntries = listOf(
                GitContentResolver.ResolvedGitEntry(
                    changeType = ChangeTypeLabel.DELETED,
                    filePath = "${rootPaths.first()}/src/Old.kt",
                    virtualFile = null,
                    contentFromRevision = null
                )
            ),
            pathResolver = ClipboardPathResolver.fromRootPaths(rootPaths),
            settings = CopyFileContentSettings.getInstance(project),
            indicator = EmptyProgressIndicator()
        )
    }

    private fun <T> withSettings(headerFormat: String, maxFileSizeKB: Int, block: () -> T): T {
        val settings = CopyFileContentSettings.getInstance(project)!!
        val prevHeader = settings.state.headerFormat
        val prevSize = settings.state.maxFileSizeKB
        settings.state.headerFormat = headerFormat
        settings.state.maxFileSizeKB = maxFileSizeKB
        return try {
            block()
        } finally {
            settings.state.headerFormat = prevHeader
            settings.state.maxFileSizeKB = prevSize
        }
    }

    // Assert a body line appears, ignoring the header/metadata lines around it.
    private fun assertContainsBody(payloadText: String, body: String) {
        assertTrue(payloadText.contains(body), "Expected body <$body> in payload:\n$payloadText")
    }
}
