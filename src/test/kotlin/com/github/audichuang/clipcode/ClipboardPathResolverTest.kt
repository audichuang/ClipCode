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
import kotlin.test.assertTrue

class ClipboardPathResolverTest {
    @Test
    fun `toClipboardPath uses primary root when nested root also matches`() {
        val root = Files.createTempDirectory("clipcode-root")
        val moduleRoot = root.resolve("module-b").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            root.systemIndependentPath()
        )

        assertEquals(
            "module-b/src/App.kt",
            resolver.toClipboardPath(moduleRoot.resolve("src/App.kt").systemIndependentPath())
        )
    }

    @Test
    fun `toClipboardPath respects primaryRoot over longer module root`() {
        val projectRoot = Files.createTempDirectory("clipcode-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals(
            "inv-svc-adv/src/test/java/cub/inv/svc/adv/loadtest/AdvPdfSaveBurstTest.java",
            resolver.toClipboardPath(
                moduleRoot.resolve("src/test/java/cub/inv/svc/adv/loadtest/AdvPdfSaveBurstTest.java")
                    .systemIndependentPath()
            )
        )
    }

    @Test
    fun `toClipboardPath prefixes sibling content root label`() {
        val workspace = Files.createTempDirectory("clipcode-workspace")
        val projectRoot = workspace.resolve("main-app").createDirectories()
        val siblingRoot = workspace.resolve("shared-lib").createDirectories()
        val file = siblingRoot.resolve("src/App.kt")
        file.parent.createDirectories()

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), siblingRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals(
            "shared-lib/src/App.kt",
            resolver.toClipboardPath(file.systemIndependentPath())
        )
    }

    @Test
    fun `sibling label colliding with primary child copies absolute and restores ambiguously`() {
        val workspace = Files.createTempDirectory("clipcode-label-collision")
        val projectRoot = workspace.resolve("main-app").createDirectories()
        val primarySharedRoot = projectRoot.resolve("shared-lib").createDirectories()
        val siblingRoot = Files.createTempDirectory("clipcode-external")
            .resolve("shared-lib")
            .createDirectories()
        val primaryFile = primarySharedRoot.resolve("src/App.kt")
        val siblingFile = siblingRoot.resolve("src/App.kt")
        primaryFile.parent.createDirectories()
        siblingFile.parent.createDirectories()
        primaryFile.writeText("primary")
        siblingFile.writeText("sibling")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), siblingRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals("shared-lib/src/App.kt", resolver.toClipboardPath(primaryFile.systemIndependentPath()))
        assertEquals(siblingFile.systemIndependentPath(), resolver.toClipboardPath(siblingFile.systemIndependentPath()))

        val writeResolution = resolver.resolveWriteTarget("shared-lib/src/App.kt")
        val ambiguousWrite = assertIs<ClipboardPathResolver.WriteResolution.Ambiguous>(writeResolution)
        assertTrue(ambiguousWrite.candidates.any { it == primaryFile.systemIndependentPath() })
        assertTrue(ambiguousWrite.candidates.any { it == siblingFile.systemIndependentPath() })

        val deleteResolution = resolver.resolveDeleteTarget("shared-lib/src/App.kt")
        val ambiguousDelete = assertIs<ClipboardPathResolver.DeleteResolution.Ambiguous>(deleteResolution)
        assertTrue(ambiguousDelete.candidates.any { it == primaryFile.systemIndependentPath() })
        assertTrue(ambiguousDelete.candidates.any { it == siblingFile.systemIndependentPath() })
    }

    @Test
    fun `resolveExistingPath resolves explicit sibling root label`() {
        val workspace = Files.createTempDirectory("clipcode-existing-sibling")
        val projectRoot = workspace.resolve("main-app").createDirectories()
        val siblingRoot = workspace.resolve("shared-lib").createDirectories()
        val file = siblingRoot.resolve("src/App.kt")
        file.parent.createDirectories()
        file.writeText("content")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), siblingRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals(siblingRoot.systemIndependentPath(), resolver.resolveExistingPath("shared-lib"))
        assertEquals(file.systemIndependentPath(), resolver.resolveExistingPath("shared-lib/src/App.kt"))
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
    fun `resolveWriteTarget prefers primary root when only legacy module directory matches`() {
        val projectRoot = Files.createTempDirectory("clipcode-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv").createDirectories()
        projectRoot.resolve("src/test/java/cub/inv/svc/adv/loadtest").createDirectories()
        moduleRoot.resolve("src/test/java/cub/inv/svc/adv/loadtest").createDirectories()
        val primaryExisting = projectRoot.resolve("src/test/java/cub/inv/svc/adv/loadtest/AdvPdfSaveBurstTest.java")
        primaryExisting.writeText("root copy")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("src/test/java/cub/inv/svc/adv/loadtest/AdvPdfSaveBurstTest.java")

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(primaryExisting.systemIndependentPath(), resolved.target.absolutePath)
        assertTrue(resolved.target.existed)
    }

    @Test
    fun `resolveWriteTarget prefers primary root when only nested module parent directory matches`() {
        val projectRoot = Files.createTempDirectory("clipcode-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv").createDirectories()
        val primaryFile = projectRoot.resolve("src/main/java/Foo.kt")
        primaryFile.parent.createDirectories()
        moduleRoot.resolve("src/main/java").createDirectories()
        primaryFile.writeText("primary")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("src/main/java/Foo.kt")

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(primaryFile.systemIndependentPath(), resolved.target.absolutePath)
        assertTrue(resolved.target.existed)
    }

    @Test
    fun `resolveWriteTarget remains ambiguous when primary and nested module files both exist`() {
        val projectRoot = Files.createTempDirectory("clipcode-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv").createDirectories()
        val primaryFile = projectRoot.resolve("src/main/java/Foo.kt")
        val moduleFile = moduleRoot.resolve("src/main/java/Foo.kt")
        primaryFile.parent.createDirectories()
        moduleFile.parent.createDirectories()
        primaryFile.writeText("primary")
        moduleFile.writeText("module")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("src/main/java/Foo.kt")

        val ambiguous = assertIs<ClipboardPathResolver.WriteResolution.Ambiguous>(resolution)
        assertTrue(ambiguous.candidates.any { it == primaryFile.systemIndependentPath() })
        assertTrue(ambiguous.candidates.any { it == moduleFile.systemIndependentPath() })
    }

    @Test
    fun `resolveWriteTarget prefers primaryRoot for explicit project-root-relative module path`() {
        val projectRoot = Files.createTempDirectory("clipcode-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv").createDirectories()
        val intendedTarget = moduleRoot.resolve("src/App.kt")
        val duplicateTarget = moduleRoot.resolve("inv-svc-adv/src/App.kt")
        intendedTarget.parent.createDirectories()
        duplicateTarget.parent.createDirectories()
        intendedTarget.writeText("intended")
        duplicateTarget.writeText("duplicate")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("inv-svc-adv/src/App.kt")

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(intendedTarget.systemIndependentPath(), resolved.target.absolutePath)
        assertTrue(resolved.target.existed)
    }

    @Test
    fun `resolveWriteTarget falls back to other roots when primaryRoot target is missing`() {
        val projectRoot = Files.createTempDirectory("clipcode-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv").createDirectories()
        val target = moduleRoot.resolve("src/main/java/Foo.kt")
        target.parent.createDirectories()
        target.writeText("module")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("src/main/java/Foo.kt")

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(target.systemIndependentPath(), resolved.target.absolutePath)
        assertTrue(resolved.target.existed)
    }

    @Test
    fun `resolveWriteTarget writes explicit sibling root label to sibling root`() {
        val workspace = Files.createTempDirectory("clipcode-write-sibling")
        val projectRoot = workspace.resolve("main-app").createDirectories()
        val siblingRoot = workspace.resolve("shared-lib").createDirectories()
        val target = siblingRoot.resolve("src/New.kt")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), siblingRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("shared-lib/src/New.kt")

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("src/New.kt", resolved.target.relativePath)
        assertEquals(target.systemIndependentPath(), resolved.target.absolutePath)
        assertEquals(siblingRoot.systemIndependentPath(), resolved.target.rootPath)
        assertFalse(resolved.target.existed)
    }

    @Test
    fun `resolveWriteTarget keeps legacy sibling path fallback when primary target is missing`() {
        val workspace = Files.createTempDirectory("clipcode-legacy-sibling")
        val projectRoot = workspace.resolve("main-app").createDirectories()
        val siblingRoot = workspace.resolve("shared-lib").createDirectories()
        val target = siblingRoot.resolve("src/App.kt")
        target.parent.createDirectories()
        target.writeText("existing sibling")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), siblingRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("src/App.kt")

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(target.systemIndependentPath(), resolved.target.absolutePath)
        assertTrue(resolved.target.existed)
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
    fun `resolveDeleteTarget deletes explicit sibling root label from sibling root`() {
        val workspace = Files.createTempDirectory("clipcode-delete-sibling")
        val projectRoot = workspace.resolve("main-app").createDirectories()
        val siblingRoot = workspace.resolve("shared-lib").createDirectories()
        val target = siblingRoot.resolve("src/Old.kt")
        target.parent.createDirectories()
        target.writeText("obsolete")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), siblingRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveDeleteTarget("shared-lib/src/Old.kt")

        val resolved = assertIs<ClipboardPathResolver.DeleteResolution.Resolved>(resolution)
        assertEquals("src/Old.kt", resolved.target.relativePath)
        assertEquals(target.systemIndependentPath(), resolved.target.absolutePath)
        assertEquals(siblingRoot.systemIndependentPath(), resolved.target.rootPath)
    }

    @Test
    fun `duplicate sibling root labels fall back to absolute copy path and ambiguous restore`() {
        val projectRoot = Files.createTempDirectory("clipcode-primary-root")
        val workspaceOne = Files.createTempDirectory("clipcode-ws-one")
        val workspaceTwo = Files.createTempDirectory("clipcode-ws-two")
        val siblingRootOne = workspaceOne.resolve("shared-lib").createDirectories()
        val siblingRootTwo = workspaceTwo.resolve("shared-lib").createDirectories()
        val file = siblingRootOne.resolve("src/App.kt")
        file.parent.createDirectories()

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(
                projectRoot.systemIndependentPath(),
                siblingRootOne.systemIndependentPath(),
                siblingRootTwo.systemIndependentPath()
            ),
            projectRoot.systemIndependentPath()
        )

        assertEquals(file.systemIndependentPath(), resolver.toClipboardPath(file.systemIndependentPath()))

        val absoluteResolution = resolver.resolveWriteTarget(file.systemIndependentPath())
        val absoluteResolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(absoluteResolution)
        assertEquals(file.systemIndependentPath(), absoluteResolved.target.absolutePath)
        assertEquals(siblingRootOne.systemIndependentPath(), absoluteResolved.target.rootPath)
        assertFalse(absoluteResolved.target.existed)

        val resolution = resolver.resolveWriteTarget("shared-lib/src/App.kt")
        val ambiguous = assertIs<ClipboardPathResolver.WriteResolution.Ambiguous>(resolution)
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun `resolveWriteTarget rejects cross machine absolute suffix when sibling root names repeat`() {
        val projectRoot = Files.createTempDirectory("clipcode-primary-root")
        val workspaceOne = Files.createTempDirectory("clipcode-ws-one")
        val workspaceTwo = Files.createTempDirectory("clipcode-ws-two")
        val siblingRootOne = workspaceOne.resolve("shared-lib").createDirectories()
        val siblingRootTwo = workspaceTwo.resolve("shared-lib").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(
                projectRoot.systemIndependentPath(),
                siblingRootOne.systemIndependentPath(),
                siblingRootTwo.systemIndependentPath()
            ),
            projectRoot.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget("D:/old-workspace/shared-lib/src/App.kt")

        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolution)
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
    fun `resolveWriteTarget prefers primary root suffix for cross machine absolute path under nested content root`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(
                "C:/workspace/cat",
                "C:/workspace/cat/inv-web-console"
            ),
            "C:/workspace/cat"
        )

        val resolution = resolver.resolveWriteTarget(
            "D:\\Users\\00508726\\Documents\\Project\\cat\\inv-web-console\\node_modules\\cub-lib-view-rootng\\styles\\cdk\\_a11y-theme.scss"
        )

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(
            "inv-web-console/node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss",
            resolved.target.relativePath
        )
    }

    @Test
    fun `resolveWriteTarget matches cross machine Windows suffix case insensitively`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(
                "C:/workspace/CAT",
                "C:/workspace/CAT/INV-WEB-CONSOLE"
            ),
            "C:/workspace/CAT"
        )

        val resolution = resolver.resolveWriteTarget(
            "D:\\Users\\00508726\\Documents\\Project\\cat\\inv-web-console\\node_modules\\cub-lib-view-rootng\\styles\\cdk\\_overlay-theme.scss"
        )

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(
            "inv-web-console/node_modules/cub-lib-view-rootng/styles/cdk/_overlay-theme.scss",
            resolved.target.relativePath
        )
    }

    @Test
    fun `resolveWriteTarget accepts Windows node_modules absolute path without matching project root name`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf("C:/workspace/current-project"),
            "C:/workspace/current-project"
        )

        val resolution = resolver.resolveWriteTarget(
            "D:\\Users\\00508726\\Documents\\Project\\cat\\inv-web-console\\node_modules\\cub-lib-view-rootng\\styles\\cdk\\_a11y-theme.scss"
        )

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(
            "node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss",
            resolved.target.relativePath
        )
    }

    @Test
    fun `resolveWriteTarget prefers existing primary child directory before node_modules fallback`() {
        val root = Files.createTempDirectory("clipcode-root-wrapper")
        root.resolve("inv-web-console").createDirectories()
        root.resolve("node_modules").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath()),
            root.systemIndependentPath()
        )

        val resolution = resolver.resolveWriteTarget(
            "D:\\Users\\00508726\\Documents\\Project\\cat\\inv-web-console\\node_modules\\cub-lib-view-rootng\\styles\\cdk\\_a11y-theme.scss"
        )

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(
            "inv-web-console/node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss",
            resolved.target.relativePath
        )
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
