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
    fun `resolveWriteTarget preserves full path when sibling root names repeat`() {
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

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("D/old-workspace/shared-lib/src/App.kt", resolved.target.relativePath)
        assertEquals(projectRoot.systemIndependentPath(), resolved.target.rootPath)
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
    fun `resolveWriteTarget preserves an absolute path from a different checkout`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf("C:/workspace/current-project"),
            "C:/workspace/current-project"
        )

        val resolution = resolver.resolveWriteTarget(
            "D:\\Users\\00508726\\Documents\\Project\\cat\\inv-web-console\\node_modules\\cub-lib-view-rootng\\styles\\cdk\\_a11y-theme.scss"
        )

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("D/Users/00508726/Documents/Project/cat/inv-web-console/node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss", resolved.target.relativePath)
        assertEquals("C:/workspace/current-project", resolved.target.rootPath)
    }

    @Test
    fun `resolveWriteTarget preserves the full path even when a child dir name matches`() {
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
        assertEquals("D/Users/00508726/Documents/Project/cat/inv-web-console/node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss", resolved.target.relativePath)
        assertEquals(root.systemIndependentPath(), resolved.target.rootPath)
    }

    @Test
    fun `resolveWriteTarget preserves full path when root name repeats`() {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf("C:/workspace/ClipCode"),
            "C:/workspace/ClipCode"
        )

        val resolution = resolver.resolveWriteTarget("D:/backup/ClipCode/nested/ClipCode/src/App.kt")
        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals("D/backup/ClipCode/nested/ClipCode/src/App.kt", resolved.target.relativePath)
        assertEquals("C:/workspace/ClipCode", resolved.target.rootPath)
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
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("D:/foreign/../secret.txt"))
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("/foreign/../secret.txt"))
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("D:/foreign/bad:name.txt"))
        assertNull((resolver.resolveDeleteTarget("../secret.txt") as? ClipboardPathResolver.DeleteResolution.Resolved))
    }

    @Test
    fun `literal absolute fallback stays in primary root and never remaps deletes`() {
        val parent = Files.createTempDirectory("clipcode-literal-path")
        try {
            val root = parent.resolve("project").createDirectories()
            val sibling = parent.resolve("backup").createDirectories()
            val outside = parent.resolve("outside").createDirectories()
            val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.systemIndependentPath(), sibling.systemIndependentPath()))
            val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(
                resolver.resolveWriteTarget("/backup/backup/new.txt")
            )
            assertEquals(root.resolve("backup/backup/new.txt").systemIndependentPath(), resolved.target.absolutePath)
            root.resolve("D").createDirectories().resolve("keep.txt").writeText("keep")
            assertIs<ClipboardPathResolver.DeleteResolution.Unresolved>(resolver.resolveDeleteTarget("D:/keep.txt"))
            Files.createSymbolicLink(root.resolve("escaped"), outside)
            assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("/escaped/new.txt"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `containment holds however many missing levels sit under an escaping link`() {
        val base = Files.createTempDirectory("clipcode-deep")
        val repo = base.resolve("repo").createDirectories()
        val outside = base.resolve("outside").createDirectories()
        Files.createSymbolicLink(repo.resolve("link"), outside)

        val resolver = ClipboardPathResolver.fromRootPaths(listOf(repo.toString()), repo.toString())
        // A fixed iteration budget counted MISSING ANCESTORS, so a path with more levels
        // than the budget gave up before reaching the link above them — and gave up by
        // ALLOWING.
        val deep = (listOf("link") + List(42) { "d" } + listOf("new.txt")).joinToString("/")
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget(deep))
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(resolver.resolveWriteTarget("link/new.txt"))

        // A deep path that stays inside is still writable.
        val inside = (List(42) { "d" } + listOf("new.txt")).joinToString("/")
        assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolver.resolveWriteTarget(inside))
    }

    @Test
    fun `a path segment of control characters is kept, not dropped`() {
        val base = Files.createTempDirectory("clipcode-ctrl")
        val repo = base.resolve("repo").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(listOf(repo.toString()), repo.toString())

        // Kotlin's isBlank is Unicode-aware and the VS Code mirror only drops EMPTY
        // segments, so this segment was discarded here and kept there — the two tools then
        // wrote DIFFERENT files for one clipboard path.
        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(
            resolver.resolveWriteTarget("\u001C/keep.txt")
        )
        assertEquals("\u001C/keep.txt", resolved.target.relativePath)
    }

    @Test
    fun `resolveWriteTarget keeps a drive path literal when a name holds a line terminator`() {
        // `^[A-Za-z]:/.*` with a full-string match said "not absolute" for these, because
        // Java's `.` skips them — so the path was dropped here while VS Code wrote it, and
        // while the POSIX twin `/a\rb.txt` was written here too. A lone \r survives the
        // \r?\n header split, so this is reachable from a real payload.
        val root = Files.createTempDirectory("clipcode-literal-line-terminator")
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath()),
            root.systemIndependentPath()
        )

        for (terminator in listOf("\r", "\u0085", "\u2028", "\u2029")) {
            val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(
                resolver.resolveWriteTarget("D:/a${terminator}b.txt")
            )
            assertEquals("D/a${terminator}b.txt", resolved.target.relativePath)
        }
    }

    @Test
    fun `resolveWriteTarget treats a drive path with a line terminator as Windows style`() {
        // With `.` in WINDOWS_STYLE_PATH this path was not Windows-style, so the cross-machine
        // suffix compared `PROJ` to the root `proj` case-sensitively and missed it. VS Code's
        // `.` differed on U+0085 alone, so the tools already disagreed there.
        val parent = Files.createTempDirectory("clipcode-suffix-line-terminator")
        val root = parent.resolve("proj").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath()),
            root.systemIndependentPath()
        )

        for (terminator in listOf("\r", "\u0085", "\u2028", "\u2029")) {
            val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(
                resolver.resolveWriteTarget("D:/elsewhere/PROJ/a${terminator}b.ts")
            )
            assertEquals("a${terminator}b.ts", resolved.target.relativePath)
        }
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')

    @Test
    fun `an external root keeps its identity through cross-machine suffix matching`() {
        val base = Files.createTempDirectory("clipcode-multiroot")
        val app = base.resolve("dest/app")
        val shared = base.resolve("dest/shared-lib")
        // The primary repo also contains a same-named folder — that collision is what makes
        // the label ambiguous and pushes resolution onto the suffix path in the first place.
        app.resolve("shared-lib").createDirectories()
        shared.createDirectories()

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(app.toString(), shared.toString()),
            app.toString()
        )
        // A payload copied on another machine carries the external file's absolute path.
        val resolution = resolver.resolveWriteTarget("/source/shared-lib/new.ts")

        val resolved = assertIs<ClipboardPathResolver.WriteResolution.Resolved>(resolution)
        assertEquals(
            shared.resolve("new.ts").normalize().toString(),
            Path.of(resolved.target.absolutePath).normalize().toString(),
            "must land in the external root, not be written over the primary repo"
        )
    }

    /**
     * Builds its own symlink instead of relying on the host. On macOS `/tmp` IS a symlink
     * to `/private/tmp`, so this bug reproduced there and was INVISIBLE on Linux — the VS
     * Code half of it stayed green with the fix reverted, and this side had no test at all.
     * A guard that can only fail on one OS is not a guard.
     *
     * The bug: containmentTarget() resolves the target through its deepest EXISTING
     * ancestor, but the ROOT side used toRealPath() and fell back to the UNRESOLVED path
     * when the root did not exist yet. The two then lived in different namespaces and a
     * perfectly safe create was refused as an escape. Mirror of pathResolver.test.ts
     * "a workspace root that does not exist yet is canonicalized like its targets".
     */
    @Test
    fun `a root that does not exist yet is canonicalized like its targets`() {
        val base = Files.createTempDirectory("clipcode-rootcanon")
        val real = base.resolve("real").createDirectories()
        Files.createSymbolicLink(base.resolve("link"), real)

        // Reached through the link, and not created yet — exactly a fresh restore target.
        val root = base.resolve("link/workspace")
        val resolver = ClipboardPathResolver.fromRootPaths(listOf(root.toString()), root.toString())
        assertIs<ClipboardPathResolver.WriteResolution.Resolved>(
            resolver.resolveWriteTarget("src/New.kt"),
            "a safe create under a not-yet-created root must be allowed")

        // The guard itself must still bite: a link inside that root pointing out of it escapes.
        val outside = base.resolve("outside").createDirectories()
        val ws2Real = real.resolve("ws2").createDirectories()
        Files.createSymbolicLink(ws2Real.resolve("out"), outside)
        val ws2 = base.resolve("link/ws2")
        val guarded = ClipboardPathResolver.fromRootPaths(listOf(ws2.toString()), ws2.toString())
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(
            guarded.resolveWriteTarget("out/escape.kt"),
            "a link leaving the root must still be refused")
    }

    @Test
    fun `restore must not escape the project through a directory symlink`() {
        val base = Files.createTempDirectory("clipcode-symdel")
        val repo = base.resolve("repo").createDirectories()
        val outside = base.resolve("outside").createDirectories()
        outside.resolve("keep.txt").writeText("must survive")
        Files.createSymbolicLink(repo.resolve("link"), outside)
        // pnpm's node_modules layout is exactly this: a link that never leaves the project.
        repo.resolve("packages/ui").createDirectories()
        repo.resolve("node_modules").createDirectories()
        Files.createSymbolicLink(repo.resolve("node_modules/ui"), repo.resolve("packages/ui"))

        val resolver = ClipboardPathResolver.fromRootPaths(listOf(repo.toString()), repo.toString())
        // Neither direction may reach outside the project through the link.
        assertIs<ClipboardPathResolver.DeleteResolution.Unresolved>(
            resolver.resolveDeleteTarget("link/keep.txt"))
        assertIs<ClipboardPathResolver.WriteResolution.Unresolved>(
            resolver.resolveWriteTarget("link/new.txt"))

        // A link that stays inside is legitimate — refusing every symlink would lock these
        // users out of restore entirely, and VS Code must agree.
        assertIs<ClipboardPathResolver.WriteResolution.Resolved>(
            resolver.resolveWriteTarget("node_modules/ui/index.ts"))
        assertIs<ClipboardPathResolver.WriteResolution.Resolved>(
            resolver.resolveWriteTarget("inside.txt"))
    }
}
