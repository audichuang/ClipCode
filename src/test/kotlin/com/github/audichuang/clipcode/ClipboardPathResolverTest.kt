package com.github.audichuang.clipcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class ClipboardPathResolverTest {
    @Test
    fun `toClipboardPath uses longest matching root`() {
        val root = Files.createTempDirectory("clipcode-root")
        val moduleRoot = root.resolve("module-b").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            root.systemIndependentPath()
        )

        assertEquals(
            "src/App.kt",
            resolver.toClipboardPath(moduleRoot.resolve("src/App.kt").systemIndependentPath())
        )
    }

    @Test
    fun `resolveWriteTarget detects ambiguous existing file across roots`() {
        val rootOne = Files.createTempDirectory("clipcode-root-one")
        val rootTwo = Files.createTempDirectory("clipcode-root-two")
        rootOne.resolve("src/App.kt").parent.createDirectories()
        rootTwo.resolve("src/App.kt").parent.createDirectories()
        rootOne.resolve("src/App.kt").writeText("one")
        rootTwo.resolve("src/App.kt").writeText("two")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(rootOne.systemIndependentPath(), rootTwo.systemIndependentPath()),
            rootOne.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("src/App.kt")
        val ambiguous = assertIs<ClipboardPathResolver.WriteResolution.Ambiguous>(resolution)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `resolveDeleteTarget finds file in second content root`() {
        val rootOne = Files.createTempDirectory("clipcode-root-primary")
        val rootTwo = Files.createTempDirectory("clipcode-root-secondary")
        val target = rootTwo.resolve("nested/Old.kt")
        target.parent.createDirectories()
        target.writeText("obsolete")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(rootOne.systemIndependentPath(), rootTwo.systemIndependentPath()),
            rootOne.systemIndependentPath()
        )

        val resolution = resolver.resolveDeleteTarget("nested/Old.kt")
        val resolved = assertIs<ClipboardPathResolver.DeleteResolution.Resolved>(resolution)
        assertEquals(target.systemIndependentPath(), resolved.target.absolutePath)
    }

    @Test
    fun `resolveWriteTarget supports windows style absolute path`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf("C:/workspace/ClipCode"),
            "C:/workspace/ClipCode"
        )

        val resolution = resolver.resolveWriteTarget("C:\\workspace\\ClipCode\\src\\main\\App.kt")
        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("src/main/App.kt", resolved.target.relativePath)
    }

    @Test
    fun `resolveWriteTarget supports current directory relative paths`() {
        val root = Files.createTempDirectory("clipcode-root-current")
        val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.systemIndependentPath()), root.systemIndependentPath())

        val resolution = resolver.resolveWriteTarget("./src/./main/App.kt")
        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("src/main/App.kt", resolved.target.relativePath)
    }

    @Test
    fun `resolveWriteTarget returns unresolved when root paths are empty`() {
        val resolver = ClipboardPathResolver.fromRootPaths(emptyList(), null)

        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("src/New.kt"))
    }

    @Test
    fun `toClipboardPath returns absolute path when outside all roots`() {
        val root = Files.createTempDirectory("clipcode-root-inside")
        val outsidePath = "/outside/workspace/ClipCode/src/App.kt"
        val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.systemIndependentPath()), root.systemIndependentPath())

        assertEquals(outsidePath, resolver.toClipboardPath(outsidePath))
    }

    @Test
    fun `toClipboardPath normalizes double slashes and trailing slash`() {
        val root = Files.createTempDirectory("clipcode-root-normalize")
        val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.systemIndependentPath()), root.systemIndependentPath())

        assertEquals(
            "src/App.kt",
            resolver.toClipboardPath("${root.systemIndependentPath()}//src//App.kt/")
        )
        assertEquals(
            "/outside/workspace/ClipCode/src/App.kt",
            resolver.toClipboardPath("/outside//workspace//ClipCode//src//App.kt/")
        )
    }

    @Test
    fun `resolveWriteTarget marks existed=false for non-existing single-root target`() {
        val root = Files.createTempDirectory("clipcode-root-new-target")
        val target = root.resolve("src/New.kt")
        val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.systemIndependentPath()), root.systemIndependentPath())

        val resolution = resolver.resolveWriteTarget("src/New.kt")
        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("src/New.kt", resolved.target.relativePath)
        assertEquals(target.systemIndependentPath(), resolved.target.absolutePath)
        assertFalse(resolved.target.existed)
    }

    @Test
    fun `resolveWriteTarget supports unambiguous absolute suffix from another machine`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf("C:/workspace/ClipCode"),
            "C:/workspace/ClipCode"
        )

        val resolution = resolver.resolveWriteTarget("D:/Users/audi/projects/ClipCode/src/main/App.kt")
        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("src/main/App.kt", resolved.target.relativePath)
    }

    @Test
    fun `resolveWriteTarget rejects ambiguous absolute suffix when root name repeats`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf("C:/workspace/ClipCode"),
            "C:/workspace/ClipCode"
        )

        val resolution = resolver.resolveWriteTarget("D:/backup/ClipCode/nested/ClipCode/src/App.kt")
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolution)
    }

    @Test
    fun `resolveDeleteTarget is unresolved when no content roots are available`() {
        val resolver = ClipboardPathResolver.fromRootPaths(emptyList(), null)

        assertIs<ClipboardPathResolver.DeleteResolution.Unresolved>(resolver.resolveDeleteTarget("src/Old.kt"))
    }

    @Test
    fun `sanitize rejects traversal and invalid segments`() {
        val root = Files.createTempDirectory("clipcode-root-safe")
        val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.systemIndependentPath()), root.systemIndependentPath())

        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("../secret.txt"))
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("bad:name.txt"))
        assertNull((resolver.resolveDeleteTarget("../secret.txt") as? ClipboardPathResolver.DeleteResolution.Resolved))
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')
}
