package com.github.audichuang.clipcode

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExternalLibraryHandlerTest : BasePlatformTestCase() {
    private val handler get() = ExternalLibraryHandler(project)

    fun testGetCleanPathReplacesClassExtensionWithJava() {
        val file = myFixture.addFileToProject("foo/Bar.class", "bytecode").virtualFile
        val cleanPath = handler.getCleanPath(file)
        assertTrue(cleanPath.endsWith(".java"), "Expected .java suffix, got: $cleanPath")
        assertFalse(cleanPath.endsWith(".class"))
    }

    fun testGetCleanPathKeepsKotlinExtensionUnchanged() {
        val file = myFixture.addFileToProject("foo/Baz.kt", "class Baz").virtualFile
        val cleanPath = handler.getCleanPath(file)
        assertTrue(cleanPath.endsWith(".kt"), "Expected .kt suffix, got: $cleanPath")
    }

    fun testShouldProcessFileRejectsSoExtension() {
        val file = myFixture.addFileToProject("libnative.so", "").virtualFile
        assertFalse(handler.shouldProcessFile(file))
    }

    fun testShouldProcessFileRejectsDllExtension() {
        val file = myFixture.addFileToProject("user32.dll", "").virtualFile
        assertFalse(handler.shouldProcessFile(file))
    }

    fun testShouldProcessFileRejectsDylib() {
        val file = myFixture.addFileToProject("libthing.dylib", "").virtualFile
        assertFalse(handler.shouldProcessFile(file))
    }

    fun testShouldProcessFileRejectsExe() {
        val file = myFixture.addFileToProject("tool.exe", "").virtualFile
        assertFalse(handler.shouldProcessFile(file))
    }

    fun testShouldProcessFileRejectsBin() {
        val file = myFixture.addFileToProject("blob.bin", "").virtualFile
        assertFalse(handler.shouldProcessFile(file))
    }

    fun testShouldProcessFileRejectsDirectory() {
        myFixture.addFileToProject("folder/inner.txt", "x")
        val dir = myFixture.findFileInTempDir("folder")
            ?: error("Unable to locate created directory")
        assertTrue(dir.isDirectory)
        assertFalse(handler.shouldProcessFile(dir))
    }

    fun testShouldProcessFileAcceptsKotlinFile() {
        val file = myFixture.addFileToProject("Foo.kt", "class Foo").virtualFile
        assertTrue(handler.shouldProcessFile(file))
    }

    fun testShouldProcessFileAcceptsJavaFile() {
        val file = myFixture.addFileToProject("Foo.java", "class Foo {}").virtualFile
        assertTrue(handler.shouldProcessFile(file))
    }

    fun testIsFromExternalLibraryReturnsFalseForLocalFile() {
        val file = myFixture.addFileToProject("src/App.kt", "x").virtualFile
        assertFalse(handler.isFromExternalLibrary(file))
    }

    fun testReadContentReturnsKotlinSource() {
        val file = myFixture.addFileToProject("Foo.kt", "class Foo").virtualFile
        val content = handler.readContent(file)
        assertNotNull(content)
        assertTrue(content!!.contains("class Foo"))
    }

    fun testReadContentReturnsJavaSource() {
        val file = myFixture.addFileToProject("Bar.java", "class Bar {}").virtualFile
        val content = handler.readContent(file)
        assertNotNull(content)
        assertTrue(content!!.contains("class Bar"))
    }

    fun testReadContentReturnsTextForNonBinaryUnknownExtension() {
        val file = myFixture.addFileToProject("readme.txt", "Hello").virtualFile
        val content = handler.readContent(file)
        assertNotNull(content)
        assertTrue(content!!.contains("Hello"))
    }

    fun testReadContentReturnsNullForUnknownBinaryExtension() {
        // 副檔名 .xyz 不在 text extension 白名單，會被 isBinaryFile 判為 binary，回傳 null
        val file = myFixture.addFileToProject("blob.xyz", "anything").virtualFile
        val content = handler.readContent(file)
        assertNull(content)
    }

    fun testReadContentForGroovyFile() {
        val file = myFixture.addFileToProject("script.groovy", "println 'hi'").virtualFile
        val content = handler.readContent(file)
        assertNotNull(content)
    }

    fun testReadContentForScalaFile() {
        val file = myFixture.addFileToProject("Foo.scala", "object Foo").virtualFile
        val content = handler.readContent(file)
        assertNotNull(content)
    }

    // === strict UTF-8, same rule as the ordinary copy path ===
    // A library file used to be decoded leniently (`String(bytes, UTF_8)`) or with the
    // file's own charset (`LoadTextUtil`), so Big5 arrived as `// \uFFFD\uFFFD` and UTF-16
    // arrived as perfect text the wire cannot carry back. The payload has no encoding
    // field: whatever is copied is rewritten as UTF-8 by the receiver.

    private fun libraryFile(name: String, bytes: ByteArray) =
        myFixture.addFileToProject(name, "placeholder").virtualFile.also { file ->
            com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
                file.setBinaryContent(bytes)
            }
        }

    fun testReadContentRejectsBig5Source() {
        val file = libraryFile("Legacy.java", "// \u4e2d\u6587".toByteArray(java.nio.charset.Charset.forName("Big5")))
        assertNull(handler.readContent(file))
    }

    fun testReadContentRejectsUtf16Text() {
        val file = libraryFile("index.js", "// \u4e2d\u6587".toByteArray(Charsets.UTF_16))
        assertNull(handler.readContent(file))
    }

    fun testReadContentRejectsNulBytesInAWhitelistedExtension() {
        val file = libraryFile("data.js", byteArrayOf(65, 0, 66))
        assertNull(handler.readContent(file))
    }

    fun testReadContentKeepsAUtf8Bom() {
        val text = "\uFEFFexport const answer = 42;"
        val file = libraryFile("bom.js", text.toByteArray(Charsets.UTF_8))
        // LoadTextUtil dropped the BOM here while the ordinary copy path kept it, so the
        // same file copied from two places produced two different payloads.
        assertEquals(text, handler.readContent(file))
    }

    fun testUndecompilableClassIsSkippedRatherThanCopiedAsAnErrorComment() {
        // Real bytecode with no decompiler available. The old code returned
        // "// Error: Could not retrieve source code for X" AS THE FILE'S CONTENT — counted
        // as a copied file, and not one of the markers RestorePlan.isPlaceholderBody
        // refuses to write over a real file.
        val file = libraryFile(
            "Example.class",
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte(), 0, 0, 0, 0x41)
        )
        assertNull(handler.readContent(file))
    }

    fun testGetCleanPathForRegularFileReturnsPresentableUrl() {
        val file = myFixture.addFileToProject("src/main/App.kt", "x").virtualFile
        val cleanPath = handler.getCleanPath(file)
        assertTrue(cleanPath.contains("App.kt"))
    }
}
