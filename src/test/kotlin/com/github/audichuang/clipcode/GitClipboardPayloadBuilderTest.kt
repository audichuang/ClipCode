package com.github.audichuang.clipcode

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertFailsWith
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.testFramework.LightVirtualFile
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

    fun testCanceledFileReadDoesNotProduceErrorPlaceholder() {
        var reads = 0
        val file = object : LightVirtualFile("cancel.txt", "content") {
            override fun getLength(): Long = 7
            override fun getContent(): CharSequence { reads++; throw ProcessCanceledException() }
        }
        val entry = GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, file.path, file)
        assertFailsWith<ProcessCanceledException> { buildText(listOf(entry)) }
        assertTrue(reads > 0, "Cancellation must happen during the content read")
    }

    fun testCancellationAlsoPropagatesThroughSharedCopyReaders() {
        val file = object : LightVirtualFile("cancel.txt", "content") {
            override fun getLength(): Long = 7
            override fun getContent(): CharSequence = throw ProcessCanceledException()
            override fun contentsToByteArray(): ByteArray = throw ProcessCanceledException()
        }
        val method = CopyFileContentAction::class.java.getDeclaredMethod(
            "readFileContents", com.intellij.openapi.vfs.VirtualFile::class.java)
        method.isAccessible = true
        val failure = assertFailsWith<java.lang.reflect.InvocationTargetException> {
            method.invoke(CopyFileContentAction(), file)
        }
        assertTrue(failure.cause is ProcessCanceledException)
        assertFailsWith<ProcessCanceledException> { ExternalLibraryHandler(project).readContent(file) }
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

    // === an unreadable placeholder is not a copied file (mirror of extension.ts) ===

    fun testUnreadablePlaceholderIsNotReportedAsCopied() {
        val payload = buildPayload(
            listOf(
                GitContentResolver.ResolvedGitEntry(
                    changeType = ChangeTypeLabel.MODIFIED,
                    filePath = "/work/repo-a/src/Gone.kt",
                    virtualFile = null,
                    // The one surviving trace of a lenient decode; resolveContentEntry turns
                    // this entry into the UNREADABLE placeholder.
                    contentFromRevision = "\uFFFD"
                )
            )
        )

        assertContainsBody(payload.text, "// Unable to read file content")
        assertEquals("0 files copied (1 skipped: not UTF-8 text or unreadable).", payload.summary)
    }

    fun testUnreadablePlaceholderDoesNotConsumeTheFileCountLimit() {
        val payload = withFileCountLimit(1) {
            buildPayload(
                listOf(
                    GitContentResolver.ResolvedGitEntry(
                        changeType = ChangeTypeLabel.MODIFIED,
                        filePath = "/work/repo-a/src/Gone.kt",
                        virtualFile = null,
                        contentFromRevision = "\uFFFD"
                    ),
                    GitContentResolver.ResolvedGitEntry(
                        changeType = ChangeTypeLabel.MODIFIED,
                        filePath = "/work/repo-a/src/Real.kt",
                        virtualFile = null,
                        contentFromRevision = "GOOD"
                    )
                )
            )
        }

        // Before: the placeholder ate the limit of 1 and "GOOD" never reached the clipboard,
        // while the notification claimed one file had been copied.
        assertContainsBody(payload.text, "GOOD")
        assertEquals("1 file copied (from Git history) (1 skipped: not UTF-8 text or unreadable).", payload.summary)
    }

    // === non-text files (spec non-text-files.md) ===

    private fun pngBytes(seed: Int, size: Int = 64): ByteArray =
        ByteArray(size) { i -> if (i == 0) 0x89.toByte() else if (i == 1) 0 else (i * 31 + seed).toByte() }

    private fun vfEntry(path: String, bytes: ByteArray): GitContentResolver.ResolvedGitEntry {
        val file = myFixture.addFileToProject(path, "placeholder").virtualFile
        com.intellij.openapi.application.ApplicationManager.getApplication().runWriteAction { file.setBinaryContent(bytes) }
        return GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.NEW, file.path, file, null)
    }

    private fun textEntry(path: String, text: String) = vfEntry(path, text.toByteArray())

    private fun buildWith(
        entries: List<GitContentResolver.ResolvedGitEntry>,
        includeNonText: Boolean?,
        nonTextKB: Int = 2048
    ): GitClipboardPayloadBuilder.Payload {
        val settings = CopyFileContentSettings.getInstance(project)!!
        val prev = settings.state.maxNonTextFileSizeKB
        settings.state.maxNonTextFileSizeKB = nonTextKB
        return try {
            withSettings("// file: \$FILE_PATH", maxFileSizeKB = 500) {
                if (includeNonText == null) {
                    GitClipboardPayloadBuilder.build(entries, emptyList(), ClipboardPathResolver.fromProject(project), settings, EmptyProgressIndicator())
                } else {
                    GitClipboardPayloadBuilder.build(entries, emptyList(), ClipboardPathResolver.fromProject(project), settings, EmptyProgressIndicator(), includeNonText)
                }
            }
        } finally {
            settings.state.maxNonTextFileSizeKB = prev
        }
    }

    fun testIncludeCarriesWorkingTreeNonTextFileAsBase64() {
        val png = pngBytes(1)
        val entries = listOf(vfEntry("img/logo.png", png), textEntry("src/A.kt", "val a = 1"))

        val payload = buildWith(entries, includeNonText = true)

        assertTrue(payload.text.contains(NonText.placeholderLine(png.size.toLong(), NonText.sha256Hex(png))), payload.text)
        assertTrue(payload.text.contains("val a = 1"))
        assertEquals(1, payload.nonTextCarried.size)
        assertTrue(payload.nonTextCarried[0].path.endsWith("img/logo.png"))
        assertEquals(png.size.toLong(), payload.nonTextCarried[0].size)
        assertEquals(NonTextCopy.utf8Length(payload.text), payload.utf8Bytes)
        assertEquals(0, payload.limitLeftOut)
        assertTrue(payload.summary.startsWith("2 files copied"), payload.summary)
        assertTrue(payload.summary.contains("Non-text files copied as Base64: 1."), payload.summary)
    }

    /** Spec "What is carried": the Include notice names what it could not read, not only counts it. */
    fun testIncludeNoticeNamesUnreadableEntries() {
        val gone = GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, "src/Gone<1>.kt", null, null)
        val entries = listOf(vfEntry("img/logo.png", pngBytes(4)), gone)

        // B7: after Include the skip is called "Unreadable", with its count stated once (spec lines 117-118).
        val include = buildWith(entries, includeNonText = true)
        assertEquals("1 files copied. Non-text files copied as Base64: 1.", include.summary)
        assertEquals(
            "<b>${include.summary}</b><br>Unreadable: 1<br>src/Gone&lt;1&gt;.kt<br>",
            include.noticeHtml()
        )

        // A3: today's build keeps today's sentence (it carries the count, here the png too) and names
        // the paths below it, without a second count line.
        val exclude = buildWith(entries, includeNonText = false)
        assertEquals("0 files copied (2 skipped: not UTF-8 text or unreadable).", exclude.summary)
        val notice = exclude.noticeHtml()
        assertTrue(notice.startsWith("<b>${exclude.summary}</b><br>"), notice)
        assertTrue(notice.endsWith("logo.png<br>src/Gone&lt;1&gt;.kt<br>"), notice)
        assertFalse(notice.contains("Unreadable:"), notice)
    }

    /** A3: the no-dialog / Exclude delivery (rebuild + withLimitLeftOut) still names the unreadable entry. */
    fun testExcludeNoticeNamesUnreadableEntriesWithoutChangingThePayload() {
        val gone = GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, "src/Gone.kt", null, null)
        val entries = listOf(textEntry("src/A.kt", "val a = 1"), gone)

        val today = buildWith(entries, includeNonText = false)
        val delivered = today.withLimitLeftOut(2)

        assertEquals(1, today.skipNotes.count(SkipCategory.UNREADABLE))
        assertEquals(buildWith(entries, includeNonText = null), today)
        assertTrue(delivered.noticeHtml().endsWith("left out 2 files.</b><br>src/Gone.kt<br>"), delivered.noticeHtml())
    }

    fun testExcludeAndDefaultAreTodaysPayloadByteForByte() {
        val entries = listOf(vfEntry("img/logo.png", pngBytes(1)), textEntry("src/A.kt", "val a = 1"))

        val default = buildWith(entries, includeNonText = null)
        val exclude = buildWith(entries, includeNonText = false)

        assertEquals(default, exclude)
        assertFalse(exclude.text.contains("base64 follows"))
        assertContainsBody(exclude.text, "// Unable to read file content")
        assertEquals("1 files copied (1 skipped: not UTF-8 text or unreadable).", exclude.summary)
        assertTrue(exclude.nonTextCarried.isEmpty())
        assertEquals(null, exclude.limitLeftOut)
    }

    fun testIncludeWithTextOnlyEntriesEqualsToday() {
        val entries = listOf(textEntry("src/A.kt", "val a = 1"), textEntry("src/B.kt", "val b = 2"))

        val include = buildWith(entries, includeNonText = true)
        val today = buildWith(entries, includeNonText = false)

        assertEquals(today.text, include.text)
        assertEquals(today.summary, include.summary)
        assertTrue(include.nonTextCarried.isEmpty())
    }

    fun testNonTextOverItsLimitBecomesSizePlaceholderAndIsCounted() {
        val big = pngBytes(2, size = 3 * 1024)
        val payload = buildWith(listOf(vfEntry("img/big.png", big)), includeNonText = true, nonTextKB = 1)

        assertContainsBody(payload.text, "// File skipped: size exceeds limit (${big.size} bytes)")
        assertTrue(payload.nonTextCarried.isEmpty())
        // Over both limits (text 500 KB is larger, so this one was read): non-text over limit.
        assertEquals(1, payload.nonTextOverLimit)
        assertEquals(0, payload.unreadOverLimit)
    }

    fun testFileOverBothLimitsIsNotReadAndCounted() {
        val huge = ByteArray(600 * 1024) { 'a'.code.toByte() }
        val payload = buildWith(listOf(vfEntry("data/huge.txt", huge)), includeNonText = true, nonTextKB = 1)

        assertContainsBody(payload.text, "// File skipped: size exceeds limit (${huge.size} bytes)")
        assertEquals(1, payload.unreadOverLimit)
        assertEquals(0, payload.nonTextOverLimit)
    }

    fun testNonTextFileTakesAFileCountSlotAndLimitLeftOutIsCounted() {
        val entries = listOf(
            vfEntry("img/a.png", pngBytes(1)),
            textEntry("src/B.kt", "val b = 1"),
            textEntry("src/C.kt", "val c = 1")
        )

        val payload = withFileCountLimit(1) { buildWith(entries, includeNonText = true) }

        assertEquals(1, payload.nonTextCarried.size)
        assertFalse(payload.text.contains("val b = 1"))
        assertEquals(2, payload.limitLeftOut)
        assertTrue(payload.summary.contains("The file-count limit left out 2 files."), payload.summary)
        assertTrue(payload.summary.contains("File limit 1 reached."), payload.summary)
    }

    /** D1: counting stops at 1000 left-out files; the 1001st marks the number as "1000+". */
    fun testLimitLeftOutCountIsCappedAt1000Plus() {
        val first = textEntry("cap/a0.kt", "val a = 0")
        val rest = (1..1001).map { textEntry("cap/a$it.kt", "val a = $it") }

        val payload = withFileCountLimit(1) { buildWith(listOf(first) + rest, includeNonText = true) }

        assertEquals(1000, payload.limitLeftOut)
        assertTrue(payload.limitLeftOutCapped)
        assertTrue(payload.summary.contains("The file-count limit left out 1000+ files."), payload.summary)
        assertTrue(payload.toSummary(null).limitLeftOutCapped)
    }

    fun testLimitLeftOutExactly1000IsNotCapped() {
        val first = textEntry("cap2/a0.kt", "val a = 0")
        val rest = (1..1000).map { textEntry("cap2/a$it.kt", "val a = $it") }

        val payload = withFileCountLimit(1) { buildWith(listOf(first) + rest, includeNonText = true) }

        assertEquals(1000, payload.limitLeftOut)
        assertFalse(payload.limitLeftOutCapped)
        assertTrue(payload.summary.contains("The file-count limit left out 1000 files."), payload.summary)
    }

    fun testWithLimitLeftOutRendersCappedAs1000Plus() {
        val p = GitClipboardPayloadBuilder.Payload("t", "2 files copied.")
        assertEquals("2 files copied. The file-count limit left out 1000+ files.", p.withLimitLeftOut(1000, true).summary)
    }

    fun testRevisionContentIsNeverCarriedAsNonText() {
        val file = vfEntry("img/rev.png", pngBytes(3)).virtualFile
        val entry = GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, file!!.path, file, contentFromRevision = "revision text")

        val payload = buildWith(listOf(entry), includeNonText = true)

        assertContainsBody(payload.text, "revision text")
        assertTrue(payload.nonTextCarried.isEmpty())
    }

    fun testWithLimitLeftOutAppendsSentenceOnlyWhenPositive() {
        val p = GitClipboardPayloadBuilder.Payload("t", "2 files copied.")
        assertEquals("2 files copied. The file-count limit left out 1 file.", p.withLimitLeftOut(1).summary)
        assertEquals(p, p.withLimitLeftOut(0))
        assertEquals(p, p.withLimitLeftOut(null))
    }

    private fun buildPayload(
        contentEntries: List<GitContentResolver.ResolvedGitEntry>
    ): GitClipboardPayloadBuilder.Payload = withSettings("// file: \$FILE_PATH", maxFileSizeKB = 500) {
        GitClipboardPayloadBuilder.build(
            contentEntries = contentEntries,
            deletedMarkerEntries = emptyList(),
            pathResolver = ClipboardPathResolver.fromRootPaths(listOf("/work/repo-a", "/work/repo-b")),
            settings = CopyFileContentSettings.getInstance(project),
            indicator = EmptyProgressIndicator()
        )
    }

    private fun <T> withFileCountLimit(limit: Int, block: () -> T): T {
        val settings = CopyFileContentSettings.getInstance(project)!!
        val prevSet = settings.state.setMaxFileCount
        val prevLimit = settings.state.fileCountLimit
        settings.state.setMaxFileCount = true
        settings.state.fileCountLimit = limit
        return try {
            block()
        } finally {
            settings.state.setMaxFileCount = prevSet
            settings.state.fileCountLimit = prevLimit
        }
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
