package com.github.audichuang.clipcode

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.lang.reflect.InvocationTargetException

class CopyResponsivenessTest : BasePlatformTestCase() {
    fun testGitMenuUpdatesDoNotRequestChangesOrFileContents() {
        val queries = mutableSetOf<String>()
        val event = com.intellij.openapi.actionSystem.AnActionEvent.createFromDataContext(
            "Vcs.Log.ContextMenu", null, com.intellij.openapi.actionSystem.DataContext { key ->
                queries.add(key)
                if (com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.`is`(key)) project else null
            }
        )
        queries.clear()
        val action = CopyGitFilesContentAction()
        val start = System.nanoTime()
        repeat(10_000) { action.update(event) }
        println("Git menu: 10000 updates in ${(System.nanoTime() - start) / 1e6} ms, keys=$queries")
        assertTrue(event.presentation.isEnabledAndVisible)
        assertEquals(setOf(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT.name), queries)
    }

    private fun build(files: Array<VirtualFile>, indicator: ProgressIndicator,
                      header: ((VirtualFile, String) -> String)? = null): String {
        val settings = CopyFileContentSettings.getInstance(project)!!
        settings.state.setMaxFileCount = false
        settings.state.useFilters = false
        settings.state.headerFormat = "// file: \$FILE_PATH"
        settings.state.preText = ""
        settings.state.postText = ""
        val method = CopyFileContentAction::class.java.getDeclaredMethod(
            "buildCopyPayload", Project::class.java, Array<VirtualFile>::class.java,
            CopyFileContentSettings::class.java, kotlin.jvm.functions.Function2::class.java,
            ProgressIndicator::class.java
        ).apply { isAccessible = true }
        val result = method.invoke(CopyFileContentAction(), project, files, settings, header, indicator)
        return result.javaClass.getDeclaredField("text").apply { isAccessible = true }.get(result) as String
    }

    fun testEmptyFileRoundTripsAlongsideNonEmptyFile() {
        val empty = myFixture.addFileToProject("empty.txt", "").virtualFile
        val full = myFixture.addFileToProject("full.txt", "contents").virtualFile
        val text = build(arrayOf(empty, full), EmptyProgressIndicator())
        val entries = ClipboardRestoreParser().parse(text, "// file: \$FILE_PATH")
        assertEquals(2, entries.size)
        assertTrue(entries.any { it.path.endsWith("empty.txt") && it.content.isEmpty() })
        assertTrue(entries.any { it.path.endsWith("full.txt") && it.content == "contents" })
    }

    fun testCancellationOnLastFileDoesNotReturnPartialPayload() {
        val file = myFixture.addFileToProject("last.txt", "contents").virtualFile
        val indicator = EmptyProgressIndicator()
        try {
            build(arrayOf(file), indicator) { _, path ->
                indicator.cancel()
                "// file: $path"
            }
            fail("Cancelled final file returned a payload")
        } catch (e: InvocationTargetException) {
            assertTrue(e.cause is ProcessCanceledException)
        }
    }

    fun testOverlappingFolderAndEmptyFileSelectionCopiesEachFileOnce() {
        val empty = myFixture.addFileToProject("overlap/empty.txt", "").virtualFile
        myFixture.addFileToProject("overlap/full.txt", "contents")
        val folder = myFixture.findFileInTempDir("overlap")
        for (selection in listOf(arrayOf(empty, folder), arrayOf(folder, empty))) {
            val entries = ClipboardRestoreParser().parse(
                build(selection, EmptyProgressIndicator()), "// file: \$FILE_PATH"
            )
            assertEquals(2, entries.size)
            assertEquals(1, entries.count { it.path.endsWith("empty.txt") && it.content.isEmpty() })
            assertEquals(1, entries.count { it.path.endsWith("full.txt") && it.content == "contents" })
        }
    }

    fun testDirectoryCancellationStopsBeforeNextFile() {
        repeat(30) { myFixture.addFileToProject("cancel/f$it.txt", "contents") }
        val indicator = EmptyProgressIndicator()
        var visited = 0
        try {
            build(arrayOf(myFixture.findFileInTempDir("cancel")), indicator) { _, path ->
                visited++
                indicator.cancel()
                "// file: $path"
            }
            fail("Cancellation ignored; visited $visited files")
        } catch (e: InvocationTargetException) {
            assertTrue(e.cause is ProcessCanceledException)
            assertEquals(1, visited)
        }
    }
}
