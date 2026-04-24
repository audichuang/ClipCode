package com.github.audichuang.clipcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

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

    private fun planFor(root: Path, entries: List<ClipboardRestoreParser.ParsedClipboardEntry>): RestorePlan {
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(root.systemIndependentPath()),
            root.systemIndependentPath()
        )
        return RestorePlanBuilder(resolver).build(entries)
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')
}
