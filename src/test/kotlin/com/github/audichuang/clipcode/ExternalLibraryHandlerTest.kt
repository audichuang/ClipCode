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

    fun testGetCleanPathForRegularFileReturnsPresentableUrl() {
        val file = myFixture.addFileToProject("src/main/App.kt", "x").virtualFile
        val cleanPath = handler.getCleanPath(file)
        assertTrue(cleanPath.contains("App.kt"))
    }
}
