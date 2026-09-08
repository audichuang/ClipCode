package com.github.audichuang.clipcode

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandEvent
import com.intellij.openapi.command.CommandListener
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.VcsDirectoryMapping
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.vcsUtil.VcsUtil
import git4idea.GitContentRevision
import git4idea.GitRevisionNumber
import git4idea.GitVcs
import java.io.File

class BatchOperationsTest : BasePlatformTestCase() {
    override fun tearDown() {
        try { ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(emptyList()) }
        finally { super.tearDown() }
    }

    fun testLargeRestoreReleasesWriteCommandBetweenBatches() {
        val root = File(project.basePath!!, "restore").apply { mkdirs() }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
        val plan = RestorePlan((0 until 300).map {
            RestorePlan.CreateOperation("f$it.txt", "${root.path}/f$it.txt", root.path, "x".repeat(4096), false)
        }, emptyList(), emptyList())
        val durations = mutableListOf<Double>()
        var started = 0L
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun commandStarted(event: CommandEvent) { started = System.nanoTime() }
            override fun commandFinished(event: CommandEvent) { durations.add((System.nanoTime() - started) / 1e6) }
        })
        val start = System.nanoTime()
        val result = try { RestoreExecutor(project).execute(plan, false, false) } finally { connection.disconnect() }
        println("BATCH restore 300: total=${(System.nanoTime()-start)/1e6} ms commands=${durations.size} max=${durations.maxOrNull()} ms")
        assertEquals(300, result.createdCount)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertTrue("All 300 files held one write command", durations.size > 1)
        plan.createOperations.forEach { assertEquals(it.content, File(it.absolutePath).readText()) }
    }

    fun testBatchedRestoreCanUndoAndRedoTogether() {
        val root = File(project.basePath!!, "undo").apply { mkdirs() }
        File(root, "f0.txt").writeText("before")
        File(root, "deleted.txt").writeText("restore on undo")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!.refresh(false, true)
        val plan = RestorePlan((0 until 70).map {
            RestorePlan.CreateOperation("f$it.txt", "${root.path}/f$it.txt", root.path, "value $it", it == 0)
        }, listOf(RestorePlan.DeleteOperation("deleted.txt", "${root.path}/deleted.txt")), emptyList())
        val result = RestoreExecutor(project).execute(plan, true, false)
        assertEquals(69, result.createdCount)
        assertEquals(1, result.overwrittenCount)
        assertEquals(1, result.deletedCount)
        val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
        assertTrue("Restore must be undoable", undo.isUndoAvailable(null))
        undo.undo(null)
        plan.createOperations.drop(1).forEach { assertFalse("Undo missed ${it.relativePath}", File(it.absolutePath).exists()) }
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("before", File(root, "f0.txt").readText())
        assertEquals("restore on undo", File(root, "deleted.txt").readText())
        undo.redo(null)
        com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
        plan.createOperations.forEach { assertEquals(it.content, File(it.absolutePath).readText()) }
        assertFalse(File(root, "deleted.txt").exists())
        val next = RestorePlan(listOf(RestorePlan.CreateOperation("next.txt", "${root.path}/next.txt", root.path, "next", false)), emptyList(), emptyList())
        RestoreExecutor(project).execute(next, false, false)
        undo.undo(null)
        assertFalse(File(root, "next.txt").exists())
        assertTrue("Separate restores must not merge", File(root, "f1.txt").exists())
    }

    fun testCancellationBetweenBatchesKeepsAccuratePartialResult() {
        val root = File(project.basePath!!, "cancel").apply { mkdirs() }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
        val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
        val plan = RestorePlan((0 until 100).map {
            RestorePlan.CreateOperation("f$it.txt", "${root.path}/f$it.txt", root.path, "value", false)
        }, emptyList(), emptyList())
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun commandFinished(event: CommandEvent) { indicator.cancel() }
        })
        val result = try { RestoreExecutor(project).execute(plan, false, false, indicator) }
            finally { connection.disconnect() }
        assertTrue(result.cancelled)
        assertTrue(result.createdCount in 1..32)
        assertEquals(result.createdCount, root.listFiles()!!.size)
        assertTrue(result.errors.isEmpty())
        com.intellij.openapi.command.undo.UndoManager.getInstance(project).undo(null)
        assertEquals(0, root.listFiles()!!.size)
    }

    fun testBackgroundRestoreAllowsUiEventsBetweenBatches() {
        val root = File(project.basePath!!, "responsive").apply { mkdirs() }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
        val plan = RestorePlan((0 until 1000).map {
            RestorePlan.CreateOperation("f$it.txt", "${root.path}/f$it.txt", root.path, "x".repeat(4096), false)
        }, emptyList(), emptyList())
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var heartbeats = 0
        val durations = mutableListOf<Double>()
        var started = 0L
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun commandStarted(event: CommandEvent) { started = System.nanoTime() }
            override fun commandFinished(event: CommandEvent) {
                durations.add((System.nanoTime() - started) / 1e6)
                javax.swing.SwingUtilities.invokeLater { if (!done.get()) heartbeats++ }
            }
        })
        val start = System.nanoTime()
        val future = ApplicationManager.getApplication().executeOnPooledThread(java.util.concurrent.Callable {
            try { RestoreExecutor(project).execute(plan, false, false) } finally { done.set(true) }
        })
        val result = try { com.intellij.testFramework.PlatformTestUtil.waitForFuture(future, 60_000) }
            finally { connection.disconnect() }
        println("BATCH background restore 1000: total=${(System.nanoTime()-start)/1e6} ms maxCommand=${durations.maxOrNull()} ms UI events=$heartbeats")
        assertEquals(1000, result.createdCount)
        assertTrue(result.errors.toString(), result.errors.isEmpty())
        assertTrue("UI did not receive events before restore completed", heartbeats > 1)
    }

    fun testRestoreVcsIgnoreIsScopedToPlannedPathsAndCleared() {
        val root = File(project.basePath!!, "vcs-scope").apply { mkdirs() }
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)
        val path = "${root.path}/file.txt"
        val provider = RestoreVcsIgnoreProvider()
        val selected = VcsUtil.getFilePath(File(path), false)
        val unrelated = VcsUtil.getFilePath(File(root, "other.txt"), false)
        val connection = ApplicationManager.getApplication().messageBus.connect()
        var checked = false
        connection.subscribe(CommandListener.TOPIC, object : CommandListener {
            override fun commandStarted(event: CommandEvent) {
                assertTrue(provider.isAdditionIgnored(project, selected))
                assertTrue(provider.isDeletionIgnored(project, selected))
                assertFalse(provider.isAdditionIgnored(project, unrelated))
                checked = true
            }
        })
        try {
            val plan = RestorePlan(listOf(RestorePlan.CreateOperation("file.txt", path, root.path, "value", false)), emptyList(), emptyList())
            assertEquals(1, RestoreExecutor(project).execute(plan, false, false).createdCount)
        } finally { connection.disconnect() }
        assertTrue(checked)
        assertFalse(provider.isAdditionIgnored(project, selected))
        assertNull(project.getUserData(RestoreVcsIgnoreProvider.PATHS))
    }

    fun testGitBatchMatchesNativeFiltersAndSpecialPaths() {
        val root = File(project.basePath!!).apply { mkdirs() }
        fun git(vararg args: String): String {
            val p = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            assertEquals(text, 0, p.waitFor()); return text.trim()
        }
        git("init"); git("config", "user.email", "test@example.com"); git("config", "user.name", "Test")
        val names = listOf("with space.txt", "中文.txt", "tab\tname.txt", "line\nbreak.txt", "empty.txt", "utf16.txt")
        names.forEach { File(root, it).writeText(if (it == "empty.txt") "" else "hello 中文\nsecond line\n") }
        File(root, "utf16.txt").writeBytes(byteArrayOf(-1, -2) + "UTF16 中文\n".toByteArray(Charsets.UTF_16LE))
        File(root, ".gitattributes").writeText("*.txt text eol=crlf\nutf16.txt -text\n")
        git("add", "."); git("commit", "-m", "fixture")
        val sha = git("rev-parse", "HEAD")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!.refresh(false, true)
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(root.path, GitVcs.NAME)))
        val revisions = names.map { GitContentRevision.createRevision(VcsUtil.getFilePath(File(root, it), false), GitRevisionNumber(sha), project) }
        val key = git4idea.util.GitFileUtils.READ_CONTENT_WITH
        val old = com.intellij.openapi.options.advanced.AdvancedSettings.getEnum(key, git4idea.util.GitTextConvMode::class.java)
        val cache = ProjectLevelVcsManager.getInstance(project).contentRevisionCache
        try {
            for (mode in git4idea.util.GitTextConvMode.values()) {
                com.intellij.openapi.options.advanced.AdvancedSettings.setEnum(key, mode)
                cache.clearAll()
                val native = revisions.map { it.content }
                cache.clearAll()
                val batch = GitBatchContentReader.read(project, revisions)
                if (mode == git4idea.util.GitTextConvMode.FILTERS) {
                    assertFalse("Transformed content must use the native reader", batch.containsKey(revisions.first()))
                } else {
                    assertTrue("Batch unexpectedly fell back for $mode", batch.containsKey(revisions.first()))
                }
                revisions.forEachIndexed { index, revision ->
                    assertEquals("$mode ${revision.file.name}", native[index], batch[revision] ?: revision.content)
                }
            }
        } finally {
            com.intellij.openapi.options.advanced.AdvancedSettings.setEnum(key, old)
            cache.clearAll()
        }
    }

    fun testCancelledHistoryDoesNotStartBatchWork() {
        val indicator = com.intellij.openapi.progress.EmptyProgressIndicator()
        kotlin.test.assertFailsWith<com.intellij.openapi.progress.ProcessCanceledException> {
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(Runnable {
                indicator.cancel()
                GitBatchContentReader.read(project, emptyList())
            }, indicator)
        }
    }

    fun testOverwritePreservesLineEndingsAndBinaryUndo() {
        val root = File(project.basePath!!, "encodings").apply { mkdirs() }
        val cases = mapOf("mixed.txt" to "a\r\nb\rc\n", "image.png" to "replacement", "old-crlf.txt" to "new\nline\n")
        for ((name, content) in cases) {
            val before = if (name == "old-crlf.txt") "old\r\nline\r\n".toByteArray() else byteArrayOf(1, 2, 3, 4)
            val file = File(root, name).apply { writeBytes(before) }
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
            if (name.endsWith(".txt")) com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)
            val plan = RestorePlan(listOf(RestorePlan.CreateOperation(name, file.path, root.path, content, true)), emptyList(), emptyList())
            val result = RestoreExecutor(project).execute(plan, true, false)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(content, file.readText())
            val undo = com.intellij.openapi.command.undo.UndoManager.getInstance(project)
            undo.undo(null)
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            kotlin.test.assertContentEquals(before, file.readBytes())
            undo.redo(null)
            com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().saveAllDocuments()
            assertEquals(content, file.readText())
        }
    }

    fun testHistoricalReadBenchmarkAndExactContents() {
        val root = File(project.basePath!!).apply { mkdirs() }
        fun git(vararg args: String): String {
            val p = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
            return out.trim()
        }
        git("init"); git("config", "user.email", "test@example.com"); git("config", "user.name", "Test")
        val expected = (0 until 200).associate { "history/file $it.txt" to "committed $it\n" }
        expected.forEach { (name, text) -> File(root, name).apply { parentFile.mkdirs(); writeText(text) } }
        git("add", "."); git("commit", "-m", "fixture")
        val sha = git("rev-parse", "HEAD")
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!.refresh(false, true)
        ProjectLevelVcsManager.getInstance(project).setDirectoryMappings(listOf(VcsDirectoryMapping(root.path, GitVcs.NAME)))
        val changes = expected.keys.map { name -> Change(null, GitContentRevision.createRevision(
            VcsUtil.getFilePath(File(root, name), false), GitRevisionNumber(sha), project
        )) }
        val selection = GitSelectionCollector.Selection(changes, emptyList(), emptySet(), emptySet(), SelectionSource.GIT_LOG_OR_HISTORY)
        expected.keys.forEach { File(root, it).writeText("working tree") }
        repeat(2) { round ->
            val start = System.nanoTime()
            val entries = GitContentResolver(Logger.getInstance(javaClass)).resolve(project, selection)
            println("BATCH history 200 round=$round: ${(System.nanoTime()-start)/1e6} ms")
            assertEquals(200, entries.size)
            entries.forEach { assertEquals(expected[root.toPath().relativize(File(it.filePath).toPath()).toString()], it.contentFromRevision) }
        }
    }
}
