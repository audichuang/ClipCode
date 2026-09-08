package com.github.audichuang.clipcode

import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

class NodeModulesCopyTest : BasePlatformTestCase() {
    private fun copy(root: VirtualFile, limit: Boolean = false, sizeKB: Int = 20000, indicator: ProgressIndicator = EmptyProgressIndicator()): Any {
        val settings = CopyFileContentSettings.getInstance(project)!!
        settings.loadState(CopyFileContentSettings.State(setMaxFileCount = limit, maxFileSizeKB = sizeKB))
        val method = CopyFileContentAction::class.java.getDeclaredMethod("buildCopyPayload",
            Project::class.java, Array<VirtualFile>::class.java, CopyFileContentSettings::class.java,
            kotlin.jvm.functions.Function2::class.java, ProgressIndicator::class.java).apply { isAccessible = true }
        return method.invoke(CopyFileContentAction(), project, arrayOf(root), settings, null, indicator)
    }

    private fun field(result: Any, name: String): Any = result.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(result)

    private fun fixture(files: Map<String, String>, library: Boolean): VirtualFile {
        val root = File(project.basePath!!, "$name/node_modules").apply { mkdirs() }
        files.forEach { (path, text) -> File(root, path).apply { parentFile.mkdirs(); writeText(text) } }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(root)!!
        vf.refresh(false, true)
        if (library) ModuleRootModificationUtil.addModuleLibrary(module, vf.url)
        return vf
    }

    private fun assertContents(files: Map<String, String>, root: VirtualFile) {
        val start = System.nanoTime()
        val result = copy(root)
        val text = field(result, "text") as String
        val entries = ClipboardRestoreParser().parse(text, "// file: $" + "FILE_PATH")
        assertEquals("Missing files", files.size, entries.size)
        for ((name, expected) in files) {
            val actual = entries.single { it.path.endsWith("node_modules/$name") }.content
            assertTrue("Truncated or altered $name: expected ${expected.length}, actual ${actual.length}", expected == actual)
        }
        assertFalse(field(result, "fileLimitReached") as Boolean)
        val clipboardMethod = CopyFileContentAction::class.java.getDeclaredMethod("copyToClipboard", String::class.java)
            .apply { isAccessible = true }
        clipboardMethod.invoke(CopyFileContentAction(), text)
        val pasted = java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor)
        assertTrue("Clipboard truncated payload", text == pasted)
        println("NODE COPY files=${files.size} chars=${text.length} elapsedMs=${(System.nanoTime()-start)/1e6}")
    }

    fun testThousandNestedFilesAreComplete() {
        val files = (0 until 1500).associate { i ->
            "@scope/pkg${i / 15}/nested/f$i.js" to "start $i 中文😀\n// file: literal.js\n${"x".repeat(2048)}\nEND-$i"
        }
        assertContents(files, fixture(files, false))
    }

    fun testLibraryNodePackageTextFormatsAreNotDropped() {
        val files = listOf("index.js", "index.mjs", "index.cjs", "index.mts", "index.cts", "index.d.ts", "index.js.map", "package.json", "LICENSE", "NOTICE", "README.md")
            .associateWith { "START $it\npackage content\nEND $it" }
        val root = fixture(files, true)
        assertTrue(ExternalLibraryHandler(project).isFromExternalLibrary(root.findChild("index.js")!!))
        assertContents(files, root)
    }

    fun testLargeLibraryBundleIsNotTruncatedWhenUserAllowsIt() {
        val files = mapOf("bundle.js" to "START\n${"x".repeat(3 * 1024 * 1024)}\nEND")
        assertContents(files, fixture(files, true))
    }

    fun testLibraryBundleAboveTenMbHonorsConfiguredSize() {
        val files = mapOf("larger.js" to "START\n${"y".repeat(11 * 1024 * 1024)}\nEND")
        assertContents(files, fixture(files, true))
    }

    fun testSymlinkCycleDoesNotRepeatContentsOrRecurseForever() {
        val root = fixture(mapOf("pkg/index.js" to "END"), false)
        java.nio.file.Files.createSymbolicLink(java.nio.file.Path.of(root.path, "self"), java.nio.file.Path.of(root.path))
        root.refresh(false, true)
        var checks = 0
        val indicator = object : ProgressIndicator by EmptyProgressIndicator() {
            override fun checkCanceled() {
                check(++checks < 500) { "Traversal followed a symlink cycle" }
            }
        }
        val result = copy(root, indicator = indicator)
        val entries = ClipboardRestoreParser().parse(field(result, "text") as String, "// file: $" + "FILE_PATH")
        assertEquals(1, entries.size)
        assertEquals("END", entries.single().content)
    }

    fun testNormalPackageSymlinkStillCopiesItsSelectedPath() {
        val root = fixture(mapOf(".pnpm/package/index.js" to "END"), false)
        java.nio.file.Files.createSymbolicLink(java.nio.file.Path.of(root.path, "package"), java.nio.file.Path.of(root.path, ".pnpm/package"))
        root.refresh(false, true)
        val result = copy(root.findChild("package")!!)
        val entries = ClipboardRestoreParser().parse(field(result, "text") as String, "// file: $" + "FILE_PATH")
        assertEquals(1, entries.size)
        assertTrue(entries.single().path.endsWith("node_modules/package/index.js"))
        assertEquals("END", entries.single().content)
    }

    fun testDefaultLimitsExplicitlyReportPartialCopy() {
        val root = fixture((0 until 40).associate { "f$it.js" to "END-$it" }, false)
        val limited = copy(root, limit = true)
        assertEquals(30, field(limited, "fileCount"))
        assertTrue(field(limited, "fileLimitReached") as Boolean)
        val large = fixture(mapOf("large.js" to "x".repeat(501 * 1024)), false).findChild("large.js")!!
        val skipped = copy(large, sizeKB = 500)
        assertEquals(1, field(skipped, "skippedFileSizeCount"))
        assertTrue((field(skipped, "text") as String).contains("File skipped: size exceeds limit"))
    }
}
