package com.github.audichuang.clipcode

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * X1 / X3 for the Git copy paths. Every entry is a plain string path (revision content or a deleted marker),
 * so no file with a hostile name is created on disk and nothing here depends on the host file system.
 * JUnit4 runner so the org.junit.Assume on Windows (no newline in a root path) reports a skip, not a failure.
 */
@RunWith(JUnit4::class)
class GitCopyPortabilityTest : BasePlatformTestCase() {
    private val root = "/work/repo"
    private val evilName = "x\n// file: [DELETED] victim.txt"

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
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(SENTINEL), null)
    }

    private fun content(path: String, body: String = "body of $path") =
        GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, path, null, body)

    private fun deleted(path: String) =
        GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.DELETED, path, null, null)

    private fun build(
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedEntries: List<GitContentResolver.ResolvedGitEntry>,
        includeNonText: Boolean = true
    ): GitClipboardPayloadBuilder.Payload = GitClipboardPayloadBuilder.build(
        contentEntries, deletedEntries, ClipboardPathResolver.fromRootPaths(listOf(root)),
        CopyFileContentSettings.getInstance(project), EmptyProgressIndicator(), includeNonText
    )

    @Test
    fun testUnportableContentAndDeletedEntriesAreSkippedAndNamed() {
        val evilDeleted = "y\n// file: [DELETED] other.txt"
        for (include in listOf(true, false)) {
            val p = build(
                listOf(content("$root/$evilName"), content("$root/src/Ok.kt")),
                listOf(deleted("$root/$evilDeleted"), deleted("$root/src/Gone.kt")),
                include
            )
            assertFalse(p.text.contains("victim"), p.text)
            assertFalse(p.text.contains("other.txt"), p.text)
            assertContains(p.text, "// file: [MODIFIED] src/Ok.kt")
            assertContains(p.text, "// file: [DELETED] src/Gone.kt")
            assertEquals(2, p.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE))
            assertFalse(p.nothingToCopy)
            assertContains(p.noticeHtml(), "Name not portable: 2")
            assertContains(p.noticeHtml(), "x\\x0a// file: [DELETED] victim.txt")
            // The header of an entry that is kept stays one line: only the two good headers exist.
            assertEquals(2, p.text.lines().count { it.startsWith("// file: ") }, p.text)
        }
    }

    @Test
    fun testUnportableEntryDoesNotSpendTheFileCountLimit() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.setMaxFileCount = true
        state.fileCountLimit = 1
        val p = build(listOf(content("$root/a\tb.txt"), content("$root/src/Ok.kt")), emptyList())
        assertContains(p.text, "// file: [MODIFIED] src/Ok.kt")
        assertEquals(1, p.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE))
    }

    @Test
    fun testPortableSelectionIsByteIdenticalWithAndWithoutNonText() {
        val contentEntries = listOf(content("$root/src/A.kt"), content("$root/B.kt"))
        val deletedEntries = listOf(deleted("$root/src/Old.kt"))
        val include = build(contentEntries, deletedEntries, includeNonText = true)
        val today = build(contentEntries, deletedEntries, includeNonText = false)
        assertEquals(today.text, include.text)
        assertEquals(0, today.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE))
    }

    @Test
    fun testEverythingSkippedIsNothingToCopy() {
        val p = build(listOf(content("$root/$evilName")), listOf(deleted("$root/a:b.txt")))
        assertTrue(p.nothingToCopy)
        assertEquals(2, p.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE))
    }

    @Test
    fun testGitActionLeavesClipboardAloneAndNamesTheSkips() {
        val messages = collectNotifications()
        copyThroughAction(listOf(content("$root/$evilName")), listOf(deleted("$root/a:b.txt")))
        assertEquals(SENTINEL, clipboardText())
        val notice = messages.singleOrNull { it.contains("<b>Nothing to copy</b>") } ?: error("$messages")
        assertContains(notice, "Name not portable: 2")
        assertContains(notice, "a:b.txt")
    }

    @Test
    fun testGitActionStillCopiesTheRestOfAMixedSelection() {
        copyThroughAction(listOf(content("$root/$evilName"), content("$root/src/Ok.kt")), listOf(deleted("$root/src/Gone.kt")))
        val text = clipboardText()
        assertContains(text, "// file: [MODIFIED] src/Ok.kt")
        assertContains(text, "[DELETED] src/Gone.kt")
        assertFalse(text.contains("victim"), text)
    }

    /**
     * W: on Windows `File.absolutePath` is `C:\...\repo\src\a.txt`. The portability rule must see the header
     * path `src/a.txt`, not a name holding `\`, or every such entry is dropped as "Name not portable" and the
     * copy says "0 files copied.". The literal `C:\` case fails on any host; the native case is this host's
     * own spelling (a `C:\` path on Windows CI).
     */
    @Test
    fun testNativeAbsolutePathIsNotDroppedAsUnportable() {
        val windowsResolver = ClipboardPathResolver.fromRootPaths(listOf("C:\\work\\repo"))
        val windows = GitClipboardPayloadBuilder.build(
            listOf(content("C:\\work\\repo\\src\\a.txt", "WIN-BODY")), listOf(deleted("C:\\work\\repo\\src\\Gone.kt")),
            windowsResolver, CopyFileContentSettings.getInstance(project), EmptyProgressIndicator(), includeNonText = false
        )
        assertEquals(0, windows.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE), windows.noticeHtml())
        assertContains(windows.text, "// file: [MODIFIED] src/a.txt\nWIN-BODY")
        assertContains(windows.text, "// file: [DELETED] src/Gone.kt")

        val dir = java.nio.file.Files.createTempDirectory("clipcode-native")
        try {
            val file = dir.resolve("src").resolve("a.txt").toFile()
            val nativeResolver = ClipboardPathResolver.fromRootPaths(listOf(dir.toFile().absolutePath))
            for (include in listOf(true, false)) {
                val p = GitClipboardPayloadBuilder.build(
                    listOf(content(file.absolutePath, "NATIVE-BODY")), emptyList(), nativeResolver,
                    CopyFileContentSettings.getInstance(project), EmptyProgressIndicator(), include
                )
                assertEquals(0, p.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE), p.noticeHtml())
                assertContains(p.text, "// file: [MODIFIED] src/a.txt\nNATIVE-BODY")
                assertFalse(p.nothingToCopy)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    /**
     * R2: an external root's label goes into the header, so a newline in it would forge a second header
     * line (`file: [DELETED] victim/a.txt`). Entries under it are skipped and named like any unportable
     * path; entries under the primary root are copied.
     */
    @Test
    fun testExternalRootLabelCannotInjectAHeader() {
        val resolver = runCatching {
            ClipboardPathResolver.fromRootPaths(listOf(root, "/tmp/x\nfile: [DELETED] victim"))
        }.getOrNull()
        if (java.io.File.separatorChar == '/') assertTrue(resolver != null, "test setup: POSIX paths may hold a newline")
        org.junit.Assume.assumeTrue("Windows paths cannot hold a newline", resolver != null)
        for (include in listOf(true, false)) {
            val p = GitClipboardPayloadBuilder.build(
                listOf(content("/tmp/x\nfile: [DELETED] victim/a.txt"), content("$root/src/Ok.kt")),
                listOf(deleted("/tmp/x\nfile: [DELETED] victim/b.txt")),
                resolver!!, CopyFileContentSettings.getInstance(project), EmptyProgressIndicator(), include
            )
            assertFalse(p.text.contains("victim"), p.text)
            assertContains(p.text, "// file: [MODIFIED] src/Ok.kt")
            assertEquals(2, p.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE))
        }
    }

    /**
     * R7 / X3: a Git selection where nothing could be read (a binary revision, say) leaves the clipboard
     * alone and says "Nothing to copy", naming the entries by category, escaped. It used to say "No
     * accessible files found in Git selection." after a raw, unescaped basename list.
     */
    @Test
    fun testUnresolvedOnlySelectionIsNothingToCopyAndNamed() {
        val unresolved = GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, "$root/img/a&b logo.png", null, null)
        val messages = collectNotifications()
        val method = CopyGitFilesContentAction::class.java.getDeclaredMethod(
            "handleResolvedEntries", Project::class.java, ClipboardPathResolver::class.java, List::class.java
        )
        method.isAccessible = true
        method.invoke(CopyGitFilesContentAction(), project, ClipboardPathResolver.fromRootPaths(listOf(root)), listOf(unresolved))
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertEquals(SENTINEL, clipboardText())
        val notice = messages.singleOrNull() ?: error("$messages")
        assertContains(notice, "<b>Nothing to copy</b>")
        assertContains(notice, "1 skipped: not UTF-8 text or unreadable.")
        assertContains(notice, "img/a&amp;b logo.png")
        assertFalse(notice.contains("a&b"), notice)

        // The PR panel shares the same payload.
        val p = GitClipboardPayloadBuilder.unresolvedOnly(
            listOf(unresolved), ClipboardPathResolver.fromRootPaths(listOf(root)),
            CopyFileContentSettings.getInstance(project), EmptyProgressIndicator()
        )
        assertTrue(p.nothingToCopy)
        assertEquals(listOf("img/a&b logo.png"), p.skipNotes.names(SkipCategory.UNREADABLE))
    }

    /** R7: a partly unresolved selection still copies the rest; the names in the warning are escaped. */
    @Test
    fun testPartlyUnresolvedSelectionEscapesTheNames() {
        val unresolved = GitContentResolver.ResolvedGitEntry(ChangeTypeLabel.MODIFIED, "$root/img/x&y.png", null, null)
        val messages = collectNotifications()
        val method = CopyGitFilesContentAction::class.java.getDeclaredMethod(
            "handleResolvedEntries", Project::class.java, ClipboardPathResolver::class.java, List::class.java
        )
        method.isAccessible = true
        method.invoke(
            CopyGitFilesContentAction(), project, ClipboardPathResolver.fromRootPaths(listOf(root)),
            listOf(unresolved, content("$root/src/Ok.kt"))
        )
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertContains(clipboardText(), "// file: [MODIFIED] src/Ok.kt")
        val warning = messages.firstOrNull { it.contains("could not be resolved") } ?: error("$messages")
        assertContains(warning, "x&amp;y.png")
        assertFalse(warning.contains("x&y"), warning)
    }

    // === Spec case 29: `q"uote.txt` / `tab<TAB>name.txt` beside `a.txt`, on every Git source ===

    private val quoteName = "q\"uote.txt"
    private val tabName = "tab\tname.txt"

    /** a.txt is the only header; the two odd names are named under "Name not portable" in the notice. */
    private fun assertOnlyATxtCopied(text: String, notice: String) {
        assertContains(text, "// file: [MODIFIED] a.txt\nbody of $root/a.txt")
        assertEquals(1, text.lines().count { it.startsWith("// file: ") }, text)
        assertFalse(text.contains("uote"), text)
        assertFalse(text.contains("name.txt"), text)
        assertContains(notice, "Name not portable: 2")
        assertContains(notice, "q&quot;uote.txt")
        assertContains(notice, "tab\\tname.txt")
    }

    /** Working-changes route: the Git copy action (Include build, then today's build, then delivery). */
    @Test
    fun testQuoteAndTabNamesBesideAPortableFileThroughTheGitAction() {
        CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
        val messages = collectNotifications()
        copyThroughAction(listOf(content("$root/$quoteName"), content("$root/a.txt")), listOf(deleted("$root/$tabName")))
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertOnlyATxtCopied(clipboardText(), awaitMessage(messages) { it.contains("Name not portable") })
    }

    /**
     * PR panel / commit route: entries resolved as revision content (what GitContentResolver hands back for
     * a SelectionSource.GIT_LOG_OR_HISTORY selection), built and delivered exactly as ClipCodePrPanel does.
     */
    @Test
    fun testQuoteAndTabNamesBesideAPortableFileThroughThePrPanelRoute() {
        CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
        val messages = collectNotifications()
        copyThroughPrPanelRoute(listOf(content("$root/$quoteName"), content("$root/a.txt"), content("$root/$tabName")))
        assertOnlyATxtCopied(clipboardText(), awaitMessage(messages) { it.contains("Name not portable") })
    }

    /** Case 29 → 30: an unportable name as the only change leaves the clipboard alone, on both routes. */
    @Test
    fun testQuoteNameAsTheOnlyChangeIsNothingToCopyOnBothRoutes() {
        for (route in listOf("action", "pr")) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(SENTINEL), null)
            val messages = collectNotifications()
            if (route == "action") copyThroughAction(listOf(content("$root/$quoteName")), emptyList())
            else copyThroughPrPanelRoute(listOf(content("$root/$quoteName")))
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            kotlin.test.assertEquals(SENTINEL, clipboardText(), route)
            val notice = messages.singleOrNull { it.contains("<b>Nothing to copy</b>") } ?: error("$route: $messages")
            assertContains(notice, "Name not portable: 1")
            assertContains(notice, "q&quot;uote.txt")
        }
    }

    /**
     * Case 29: a repository nested in the project (a second root) whose folder name holds a newline. Its
     * changes are skipped and named, no header is written for them; the outer repository's change is copied.
     * Only the changes under it leave nothing to copy. A newline cannot be in a Windows path, so the test is
     * skipped there and must run on POSIX.
     */
    @Test
    fun testNestedRepositoryWithANewlineInItsFolderNameIsSkippedAndNamed() {
        val nested = "$root/nested\n// file: [DELETED] victim"
        val resolver = runCatching { ClipboardPathResolver.fromRootPaths(listOf(root, nested)) }.getOrNull()
        if (java.io.File.separatorChar == '/') assertTrue(resolver != null, "test setup: POSIX paths may hold a newline")
        org.junit.Assume.assumeTrue("Windows paths cannot hold a newline", resolver != null)
        CopyFileContentSettings.getInstance(project)!!.state.showCopyNotification = true
        val nestedContent = listOf(content("$nested/src/x.txt"))
        val nestedDeleted = listOf(deleted("$nested/old.txt"))

        for (include in listOf(true, false)) {
            val p = GitClipboardPayloadBuilder.build(
                nestedContent + content("$root/a.txt"), nestedDeleted, resolver!!,
                CopyFileContentSettings.getInstance(project), EmptyProgressIndicator(), include
            )
            assertFalse(p.text.contains("victim"), p.text)
            assertEquals(1, p.text.lines().count { it.startsWith("// file: ") }, p.text)
            assertEquals(2, p.skipNotes.count(SkipCategory.NAME_NOT_PORTABLE))
        }

        var messages = collectNotifications()
        copyThroughAction(nestedContent + content("$root/a.txt"), nestedDeleted, resolver!!)
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertContains(clipboardText(), "// file: [MODIFIED] a.txt")
        assertFalse(clipboardText().contains("victim"), clipboardText())
        val notice = awaitMessage(messages) { it.contains("Name not portable: 2") }
        assertContains(notice, "nested\\x0a// file: [DELETED] victim/src/x.txt")

        for (route in listOf("action", "pr")) {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(SENTINEL), null)
            messages = collectNotifications()
            if (route == "action") copyThroughAction(nestedContent, nestedDeleted, resolver)
            else copyThroughPrPanelRoute(nestedContent + nestedDeleted, resolver)
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            kotlin.test.assertEquals(SENTINEL, clipboardText(), route)
            val nothing = messages.singleOrNull { it.contains("<b>Nothing to copy</b>") } ?: error("$route: $messages")
            assertContains(nothing, "Name not portable: 2")
        }
    }

    // === Spec case 36: a file-count limit whose left-out number is 0 ===

    /**
     * Limit 1 with the entry past it rejected by a filter: the limit was reached, nothing was left out by it.
     * The summary says "File limit 1 reached." and carries no left-out clause ("0+", "left out 0", "more files").
     */
    @Test
    fun testFileLimitWithNothingLeftOutHasNoLeftOutClause() {
        val state = CopyFileContentSettings.getInstance(project)!!.state
        state.setMaxFileCount = true
        state.fileCountLimit = 1
        state.useFilters = true
        state.useExcludeFilters = true
        state.useIncludeFilters = false
        state.filterRules = mutableListOf(
            CopyFileContentSettings.FilterRule(
                type = CopyFileContentSettings.FilterType.PATTERN,
                action = CopyFileContentSettings.FilterAction.EXCLUDE,
                value = "*.log",
                enabled = true
            )
        )
        state.showCopyNotification = true
        val entries = listOf(content("$root/a.txt"), content("$root/b.log"))

        val include = build(entries, emptyList(), includeNonText = true)
        assertEquals(0, include.limitLeftOut)
        assertFalse(include.limitLeftOutCapped)
        assertEquals(0, include.toSummary(CopyFileContentSettings.getInstance(project)).limitLeftOut)
        assertNoLeftOutClause(include.summary)
        assertNoLeftOutClause(build(entries, emptyList(), includeNonText = false).summary)

        val messages = collectNotifications()
        copyThroughAction(entries, emptyList())
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        assertNoLeftOutClause(awaitMessage(messages) { it.contains("File limit") })
        messages.clear()
        copyThroughPrPanelRoute(entries)
        assertNoLeftOutClause(awaitMessage(messages) { it.contains("File limit") })
    }

    /**
     * The payload notification is posted from a pooled thread when called on the EDT (see
     * CopyFileContentAction.showPayloadNotification), so it may arrive after the copy returns.
     */
    private fun awaitMessage(messages: List<String>, predicate: (String) -> Boolean): String {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            messages.filter(predicate).let { if (it.size == 1) return it.single() else if (it.size > 1) error("more than one: $messages") }
            Thread.sleep(20)
        }
        error("no matching notification: $messages")
    }

    private fun assertNoLeftOutClause(text: String) {
        assertContains(text, "File limit 1 reached.")
        listOf("0+", "left out 0", "more files", "left out").forEach { assertFalse(text.contains(it), "'$it' in: $text") }
    }

    /**
     * The PR panel's copy (ClipCodePrPanel.copySelection) from its resolved entries on: content and deleted
     * markers split as it does, the Include build, then the shared [GitClipboardPayloadBuilder.chooseAndDeliver]
     * with the panel's delivery (clipboard, then the payload notification). The panel itself needs a git4idea
     * repository and a Swing tree, so only this part runs headless.
     */
    private fun copyThroughPrPanelRoute(
        entries: List<GitContentResolver.ResolvedGitEntry>,
        resolver: ClipboardPathResolver = ClipboardPathResolver.fromRootPaths(listOf(root))
    ) {
        val settings = CopyFileContentSettings.getInstance(project)
        val content = entries.filter { it.hasContent }
        val deleted = entries.filter { it.changeType == ChangeTypeLabel.DELETED && !it.hasContent }
        val include = GitClipboardPayloadBuilder.build(content, deleted, resolver, settings, EmptyProgressIndicator(), includeNonText = true)
        GitClipboardPayloadBuilder.chooseAndDeliver(
            project, settings, include, { _, _ -> NonTextChoice.INCLUDE },
            rebuildToday = { indicator -> GitClipboardPayloadBuilder.build(content, deleted, resolver, settings, indicator, includeNonText = false) },
            deliver = { result ->
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(result.text), null)
                if (settings?.state?.showCopyNotification == true) {
                    CopyFileContentAction.showPayloadNotification(result.noticeHtml(), result.text, project)
                }
            }
        )
        com.intellij.testFramework.PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun copyThroughAction(
        contentEntries: List<GitContentResolver.ResolvedGitEntry>,
        deletedEntries: List<GitContentResolver.ResolvedGitEntry>,
        resolver: ClipboardPathResolver = ClipboardPathResolver.fromRootPaths(listOf(root))
    ) {
        val method = CopyGitFilesContentAction::class.java.getDeclaredMethod(
            "copyResolvedEntries", Project::class.java, List::class.java, List::class.java,
            ClipboardPathResolver::class.java, CopyFileContentSettings::class.java
        )
        method.isAccessible = true
        method.invoke(
            CopyGitFilesContentAction(), project, contentEntries, deletedEntries,
            resolver, CopyFileContentSettings.getInstance(project)
        )
    }

    private fun collectNotifications(): MutableList<String> {
        val messages: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()
        project.messageBus.connect(testRootDisposable).subscribe(
            Notifications.TOPIC,
            object : Notifications {
                override fun notify(notification: Notification) {
                    messages.add(notification.content)
                }
            }
        )
        return messages
    }

    private fun clipboardText(): String =
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as String

    private companion object {
        const val SENTINEL = "untouched-by-clipcode"
    }
}
