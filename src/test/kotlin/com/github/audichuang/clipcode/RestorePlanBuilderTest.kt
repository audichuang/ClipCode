package com.github.audichuang.clipcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestorePlanBuilderTest {
    @Test
    fun `builds create and delete operations from mixed clipboard entries`() {
        val root = Files.createTempDirectory("clipcode-plan-root")
        val deleteTarget = root.resolve("src/Old.kt")
        deleteTarget.parent.createDirectories()
        deleteTarget.writeText("obsolete")

        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/New.kt",
                    content = "new"
                ),
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Old.kt",
                    content = "",
                    changeTypes = setOf(ChangeTypeLabel.DELETED)
                )
            )
        )

        assertEquals(listOf("src/New.kt"), plan.createOperations.map { it.relativePath })
        assertEquals(listOf("src/Old.kt"), plan.deleteOperations.map { it.relativePath })
    }

    @Test
    fun `marks deleted file as already absent when target is missing`() {
        val root = Files.createTempDirectory("clipcode-plan-missing")
        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Missing.kt",
                    content = "",
                    changeTypes = setOf(ChangeTypeLabel.DELETED)
                )
            )
        )

        assertEquals(emptyList(), plan.deleteOperations)
        assertEquals(listOf(RestorePlan.SkipReason.ALREADY_ABSENT), plan.skippedOperations.map { it.reason })
    }

    @Test
    fun `marks ambiguous delete target when multiple roots contain file`() {
        val rootOne = Files.createTempDirectory("clipcode-plan-amb-one")
        val rootTwo = Files.createTempDirectory("clipcode-plan-amb-two")
        val duplicateRelativePath = "src/App.kt"
        listOf(rootOne, rootTwo).forEach { root ->
            val file = root.resolve(duplicateRelativePath)
            file.parent.createDirectories()
            file.writeText("content")
        }

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(rootOne.systemIndependentPath(), rootTwo.systemIndependentPath()),
            rootOne.systemIndependentPath()
        )
        val plan = RestorePlanBuilder(resolver).build(
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = duplicateRelativePath,
                    content = "",
                    changeTypes = setOf(ChangeTypeLabel.DELETED)
                )
            )
        )

        assertEquals(listOf(RestorePlan.SkipReason.AMBIGUOUS_TARGET), plan.skippedOperations.map { it.reason })
    }

    @Test
    fun `marks ambiguous write target when file exists in multiple roots`() {
        val rootOne = Files.createTempDirectory("clipcode-plan-write-amb-one")
        val rootTwo = Files.createTempDirectory("clipcode-plan-write-amb-two")
        val duplicateRelativePath = "src/App.kt"
        listOf(rootOne, rootTwo).forEach { root ->
            val file = root.resolve(duplicateRelativePath)
            file.parent.createDirectories()
            file.writeText("content")
        }

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(rootOne.systemIndependentPath(), rootTwo.systemIndependentPath()),
            rootOne.systemIndependentPath()
        )
        val plan = RestorePlanBuilder(resolver).build(
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = duplicateRelativePath,
                    content = "new content"
                )
            )
        )

        assertEquals(emptyList(), plan.createOperations)
        assertEquals(listOf(RestorePlan.SkipReason.AMBIGUOUS_TARGET), plan.skippedOperations.map { it.reason })
        assertEquals(2, plan.skippedOperations.single().candidates.size)
    }

    @Test
    fun `create operation sets existed=true when file already exists`() {
        val root = Files.createTempDirectory("clipcode-plan-existing-create")
        val existingFile = root.resolve("src/App.kt")
        existingFile.parent.createDirectories()
        existingFile.writeText("old content")

        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/App.kt",
                    content = "new content"
                )
            )
        )

        assertEquals(1, plan.createOperations.size)
        assertEquals("src/App.kt", plan.createOperations.single().relativePath)
        assertEquals(existingFile.systemIndependentPath(), plan.createOperations.single().absolutePath)
        assertEquals(true, plan.createOperations.single().existed)
    }

    @Test
    fun `legacy module-relative clipboard still overwrites module file when primary root target is missing`() {
        val projectRoot = Files.createTempDirectory("clipcode-plan-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv")
        val moduleFile = moduleRoot.resolve("src/main/java/Foo.kt")
        moduleFile.parent.createDirectories()
        moduleFile.writeText("old module content")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )
        val plan = RestorePlanBuilder(resolver).build(
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/main/java/Foo.kt",
                    content = "new module content"
                )
            )
        )

        assertEquals(emptyList(), plan.skippedOperations)
        assertEquals(1, plan.createOperations.size)
        assertEquals(moduleFile.systemIndependentPath(), plan.createOperations.single().absolutePath)
        assertEquals(true, plan.createOperations.single().existed)
    }

    @Test
    fun `legacy module-relative clipboard overwrites primary root when only module directory matches`() {
        val projectRoot = Files.createTempDirectory("clipcode-plan-project-root")
        val moduleRoot = projectRoot.resolve("inv-svc-adv")
        projectRoot.resolve("src/test/java/cub/inv/svc/adv/loadtest").createDirectories()
        moduleRoot.resolve("src/test/java/cub/inv/svc/adv/loadtest").createDirectories()
        val primaryExisting = projectRoot.resolve("src/test/java/cub/inv/svc/adv/loadtest/AdvPdfSaveBurstTest.java")
        primaryExisting.writeText("root copy")

        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath(), moduleRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )
        val plan = RestorePlanBuilder(resolver).build(
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/test/java/cub/inv/svc/adv/loadtest/AdvPdfSaveBurstTest.java",
                    content = "new module content"
                )
            )
        )

        assertEquals(emptyList(), plan.skippedOperations)
        assertEquals(1, plan.createOperations.size)
        assertEquals(primaryExisting.systemIndependentPath(), plan.createOperations.single().absolutePath)
        assertEquals(true, plan.createOperations.single().existed)
    }

    @Test
    fun `builds create operation from Windows node_modules absolute clipboard path`() {
        val root = Files.createTempDirectory("clipcode-plan-node-modules")
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath()),
            root.systemIndependentPath()
        )

        val plan = RestorePlanBuilder(resolver).build(
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "D:\\Users\\00508726\\Documents\\Project\\cat\\inv-web-console\\node_modules\\cub-lib-view-rootng\\styles\\cdk\\_a11y-theme.scss",
                    content = "content"
                )
            )
        )

        assertEquals(emptyList(), plan.skippedOperations)
        assertEquals(1, plan.createOperations.size)
        assertEquals(
            "node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss",
            plan.createOperations.single().relativePath
        )
        assertEquals(
            root.resolve("node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss").systemIndependentPath(),
            plan.createOperations.single().absolutePath
        )
    }

    @Test
    fun `builds create operation under existing primary child directory from Windows node_modules path`() {
        val root = Files.createTempDirectory("clipcode-plan-wrapper-root")
        root.resolve("inv-web-console").createDirectories()
        root.resolve("node_modules").createDirectories()
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath()),
            root.systemIndependentPath()
        )

        val plan = RestorePlanBuilder(resolver).build(
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "D:\\Users\\00508726\\Documents\\Project\\cat\\inv-web-console\\node_modules\\cub-lib-view-rootng\\styles\\cdk\\_a11y-theme.scss",
                    content = "content"
                )
            )
        )

        assertEquals(emptyList(), plan.skippedOperations)
        assertEquals(1, plan.createOperations.size)
        assertEquals(
            "inv-web-console/node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss",
            plan.createOperations.single().relativePath
        )
        assertEquals(
            root.resolve("inv-web-console/node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss")
                .systemIndependentPath(),
            plan.createOperations.single().absolutePath
        )
    }

    @Test
    fun `marks unresolved path when clipboard path is invalid`() {
        val root = Files.createTempDirectory("clipcode-plan-invalid")
        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "../outside.kt",
                    content = "oops"
                )
            )
        )

        assertEquals(listOf(RestorePlan.SkipReason.UNRESOLVED_PATH), plan.skippedOperations.map { it.reason })
    }

    @Test
    fun `marks unresolved path for delete operation with invalid path`() {
        val root = Files.createTempDirectory("clipcode-plan-del-invalid")
        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "../traversal.kt",
                    content = "",
                    changeTypes = setOf(ChangeTypeLabel.DELETED)
                )
            )
        )

        assertEquals(emptyList(), plan.deleteOperations)
        assertEquals(listOf(RestorePlan.SkipReason.UNRESOLVED_PATH), plan.skippedOperations.map { it.reason })
    }

    @Test
    fun `empty entries produce empty plan`() {
        val root = Files.createTempDirectory("clipcode-plan-empty")
        val plan = planFor(root, emptyList())
        assertTrue(plan.createOperations.isEmpty())
        assertTrue(plan.deleteOperations.isEmpty())
        assertTrue(plan.skippedOperations.isEmpty())
    }

    @Test
    fun `mixed batch with all skip reasons`() {
        val root = Files.createTempDirectory("clipcode-plan-mixed-skip")
        // 製造 ALREADY_ABSENT (delete missing) + UNRESOLVED (invalid path) 兩種
        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Missing.kt",
                    content = "",
                    changeTypes = setOf(ChangeTypeLabel.DELETED)
                ),
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "../bad.kt",
                    content = "bad"
                )
            )
        )

        val reasons = plan.skippedOperations.map { it.reason }.toSet()
        assertTrue(reasons.contains(RestorePlan.SkipReason.ALREADY_ABSENT))
        assertTrue(reasons.contains(RestorePlan.SkipReason.UNRESOLVED_PATH))
    }

    private fun planFor(root: Path, entries: List<ClipboardRestoreParser.ParsedClipboardEntry>): RestorePlan {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath()),
            root.systemIndependentPath()
        )
        return RestorePlanBuilder(resolver).build(entries)
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')
}
