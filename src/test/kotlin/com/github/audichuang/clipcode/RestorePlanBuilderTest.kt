package com.github.audichuang.clipcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
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
    fun `deleted entry with non-empty old content still yields a delete operation`() {
        // Cross-tool contract: IntelliJ's git-history copy carries the deleted file's
        // old content under the [DELETED] header. The plan must ignore the content and
        // delete — never create a file from it.
        val root = Files.createTempDirectory("clipcode-plan-del-content")
        val deleteTarget = root.resolve("src/Old.kt")
        deleteTarget.parent.createDirectories()
        deleteTarget.writeText("fun legacy() = Unit")

        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Old.kt",
                    content = "fun legacy() = Unit",
                    changeTypes = setOf(ChangeTypeLabel.DELETED)
                )
            )
        )

        assertEquals(emptyList(), plan.createOperations)
        assertEquals(listOf("src/Old.kt"), plan.deleteOperations.map { it.relativePath })
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
    fun `refuses a Windows absolute path that belongs to a different checkout`() {
        // Deliberately inverted. These paths used to be RESOLVED by anchoring on
        // `node_modules` (and on a same-named child directory): a path naming someone
        // else's machine and someone else's project was written into THIS project, with an
        // overwrite prompt that showed nothing unusual. VS Code refuses them; refusing is
        // the safe side of a guess that can silently clobber a same-named file. The
        // supported cross-machine case — a ROOT-NAME suffix match — still resolves and is
        // pinned separately.
        val root = Files.createTempDirectory("clipcode-plan-node-modules")
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

        assertEquals(emptyList(), plan.createOperations)
        assertEquals(RestorePlan.SkipReason.UNRESOLVED_PATH, plan.skippedOperations.single().reason)
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

    @Test
    fun `skips placeholder bodies instead of overwriting the real file`() {
        val root = Files.createTempDirectory("clipcode-plan-placeholder")
        val victim = root.resolve("src/Big.kt")
        victim.parent.createDirectories()
        victim.writeText("the real 600 KB file")

        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Big.kt",
                    content = "// File skipped: size exceeds limit (614400 bytes)"
                ),
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Unreadable.kt",
                    content = "// Unable to read file content"
                ),
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Failed.kt",
                    content = "// Error reading file content"
                ),
                // Deliberately inverted: this used to be restored because the body was
                // multi-line. That is exactly how a configured footer switched the guard
                // off and let a stub overwrite a real 1100-byte file. The guard now reads
                // the FIRST line, so trailing noise cannot disarm it. A genuine file whose
                // first line is this marker is the accepted false positive — the string is
                // one this tool invents.
                ClipboardRestoreParser.ParsedClipboardEntry(
                    path = "src/Real.kt",
                    content = "// File skipped: size exceeds limit (1 bytes)\nbut there is real content too"
                )
            )
        )

        assertEquals(emptyList(), plan.createOperations.map { it.relativePath })
        assertEquals(
            listOf("src/Big.kt", "src/Unreadable.kt", "src/Failed.kt", "src/Real.kt"),
            plan.skippedOperations.map { it.rawPath }
        )
        assertTrue(plan.skippedOperations.all { it.reason == RestorePlan.SkipReason.PLACEHOLDER_BODY })
        assertEquals("the real 600 KB file", victim.readText())
    }

    @Test
    fun `full chain - a footer must not turn a size-skipped placeholder back into file content`() {
        val root = Files.createTempDirectory("clipcode-chain-placeholder")
        val victim = root.resolve("src/large.txt")
        victim.parent.createDirectories()
        val original = "x".repeat(1100)
        victim.writeText(original)

        // The reported repro: the copy side substitutes a skip comment, and a configured
        // footer follows it. The footer used to be accumulated INTO that file's body,
        // making it multi-line, which defeated the placeholder guard entirely.
        val postText = "</files>"
        val payload = ClipboardPayloadFormatter.buildPayload(
            ClipboardPayloadFormatter.Options(
                headerFormat = "// file: \$FILE_PATH",
                preText = "",
                postText = postText,
                addExtraLineBetweenFiles = true,
                files = listOf(
                    ClipboardPayloadFormatter.PayloadFile(
                        path = "src/large.txt",
                        skippedReason = "size exceeds limit (1100 bytes)"
                    )
                )
            )
        )
        assertTrue(payload.contains("// File skipped: size exceeds limit (1100 bytes)"))
        assertTrue(payload.trimEnd().endsWith(postText), "the footer must really be in the payload")
        assertTrue(payload.contains(ClipboardRestoreParser.POST_TEXT_MARKER),
            "the footer must be terminated on the wire, not reconstructed from a setting")

        // A clipboard round-trip can append a final newline or rewrite the endings as CRLF.
        // Matching the footer as raw text made either of those a silent no-op, which put the
        // footer straight back into the body and defeated the placeholder guard again.
        listOf(
            "as built" to payload,
            "trailing newline" to payload + "\n",
            "CRLF" to payload.replace("\n", "\r\n")
        ).forEach { (name, text) ->
            val parsed = ClipboardRestoreParser().parse(text, "// file: \$FILE_PATH")
            assertEquals(1, parsed.size, name)
            assertTrue(!parsed[0].content.contains(postText),
                "the footer must not land in the file body ($name)")
        }

        // A payload from an older release carries no end marker, so the footer really does
        // reach the body — the guard must hold there too, with no setting to rescue it.
        val legacy = payload.split("\n").filter { it != ClipboardRestoreParser.POST_TEXT_MARKER }
            .joinToString("\n")
        val legacyEntries = ClipboardRestoreParser().parse(legacy, "// file: \$FILE_PATH")
        assertTrue(legacyEntries[0].content.contains(postText), "the legacy shape really glues the footer on")
        assertEquals(
            RestorePlan.SkipReason.PLACEHOLDER_BODY,
            planFor(root, legacyEntries).skippedOperations.single().reason
        )

        val entries = ClipboardRestoreParser().parse(payload, "// file: \$FILE_PATH")
        assertEquals(1, entries.size)

        val plan = planFor(root, entries)
        assertTrue(plan.createOperations.isEmpty(), "a placeholder must never be written")
        assertEquals(RestorePlan.SkipReason.PLACEHOLDER_BODY, plan.skippedOperations.single().reason)
        assertEquals(original, victim.readText(), "the real 1100-byte file must survive")
    }

    @Test
    fun `full chain - a footer is not appended to the last real file either`() {
        val root = Files.createTempDirectory("clipcode-chain-footer")
        val postText = "Please review the code above."
        val payload = ClipboardPayloadFormatter.buildPayload(
            ClipboardPayloadFormatter.Options(
                headerFormat = "// file: \$FILE_PATH",
                preText = "HEADER NOTE",
                postText = postText,
                addExtraLineBetweenFiles = true,
                files = listOf(
                    ClipboardPayloadFormatter.PayloadFile(path = "src/A.kt", content = "class A"),
                    ClipboardPayloadFormatter.PayloadFile(path = "src/B.kt", content = "class B")
                )
            )
        )
        val entries = ClipboardRestoreParser().parse(payload, "// file: \$FILE_PATH")
        assertEquals(2, entries.size)
        assertEquals("class B", entries[1].content, "the footer must not be glued onto the last file")

        val plan = planFor(root, entries)
        assertEquals(listOf("src/A.kt", "src/B.kt"), plan.createOperations.map { it.relativePath })
    }

    @Test
    fun `a non-UTF-8 file on disk is never overwritten with UTF-8 bytes`() {
        val root = Files.createTempDirectory("clipcode-plan-encoding")
        // Big5 for a CJK word: valid text here with the right project charset, and invalid
        // UTF-8. The wire format carries no encoding, so writing the payload back as UTF-8
        // changes the file's encoding with nothing said and nothing able to undo it.
        val big5 = byteArrayOf(0xa4.toByte(), 0xe9.toByte(), 0xa5.toByte(), 0xbb.toByte(), 0x0a)
        Files.write(root.resolve("legacy.txt"), big5)
        root.resolve("plain.txt").writeText("ascii\n")

        val plan = planFor(
            root,
            listOf(
                ClipboardRestoreParser.ParsedClipboardEntry(path = "legacy.txt", content = "replacement"),
                ClipboardRestoreParser.ParsedClipboardEntry(path = "plain.txt", content = "replacement")
            )
        )

        assertEquals(listOf("plain.txt"), plan.createOperations.map { it.relativePath })
        assertEquals(RestorePlan.SkipReason.NON_UTF8_TARGET, plan.skippedOperations.single().reason)
        assertTrue(Files.readAllBytes(root.resolve("legacy.txt")).contentEquals(big5),
            "the original bytes must survive")
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
