package com.github.audichuang.clipcode

import com.google.gson.Gson
import com.google.gson.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-tool contract verification against the shared golden fixtures.
 *
 * `clipboard-contract.json` is committed BYTE-IDENTICALLY here and in the VS Code
 * repo (ClipCodeVSCode/test/fixtures/clipboard-contract.json); it is generated from
 * the VS Code implementation (the format authority — see AGENTS.md). This test drives
 * the IntelliJ build ([ClipboardPayloadFormatter]) and parse ([ClipboardRestoreParser])
 * against those frozen bytes, so any drift from the contract fails here. The VS Code
 * side runs the mirror test (test/contract.test.ts) against the same file.
 *
 * Regenerate via ClipCodeVSCode/scripts/gen-contract-fixtures.cjs, copy the JSON to
 * both repos, and update EXPECTED_FIXTURES_SHA on BOTH sides.
 */
class ContractFixturesTest {
    private companion object {
        const val EXPECTED_FIXTURES_SHA = "df317eb7b412d4bd71222d71d4cd64a1652fbcac2d82468ec417e4ce95ec2468"
        const val RESOURCE = "/clipboard-contract.json"
    }

    private data class Fixtures(
        val buildCases: List<BuildCase>,
        val parseCases: List<ParseCase>,
        val tokenCases: List<TokenCase>,
        val pathLayout: PathLayout,
        val pathCases: List<PathCase>,
        val restoreLayout: RestoreLayout,
        val restoreCases: List<RestoreCase>
    )
    private data class FileSpec(val text: String?, val base64: String?)
    private data class RestoreLayout(
        val roots: List<String>,
        val dirs: List<String>,
        val files: Map<String, FileSpec>,
        val symlinks: Map<String, String>
    )
    private data class PlannedCreate(val root: String, val path: String, val relativePath: String, val content: String, val existed: Boolean)
    private data class PlannedDelete(val root: String, val path: String, val relativePath: String)
    private data class PlannedSkip(val rawPath: String, val relativePath: String?, val reason: String)
    private data class RestoreCase(
        val name: String,
        val headerFormat: String,
        val payload: String,
        val needsSymlink: Boolean?,
        val creates: List<PlannedCreate>,
        val deletes: List<PlannedDelete>,
        val skips: List<PlannedSkip>
    )
    private data class PathLayout(
        val roots: List<String>,
        val dirs: List<String>,
        val files: Map<String, String>,
        val symlinks: Map<String, String>
    )
    /** `write` / `delete` are either {root, path} or "refused" | "missing" | "ambiguous". */
    private data class PathCase(val input: String, val needsSymlink: Boolean?, val write: JsonElement, val delete: JsonElement)
    private data class BuildCase(val name: String, val kind: String, val options: FxOptions, val wire: String)
    private data class FxOptions(
        val headerFormat: String,
        val preText: String,
        val postText: String,
        val addExtraLineBetweenFiles: Boolean,
        val files: List<FxFile>,
        val sourceRoot: String?
    )
    private data class FxFile(val path: String, val content: String?, val changeType: String?, val skippedReason: String?)
    private data class ParseCase(val name: String, val headerFormat: String, val input: String, val expected: List<FxEntry>)
    private data class FxEntry(val path: String, val content: String, val changeTypes: List<String>)
    private data class TokenCase(
        val name: String,
        val text: String,
        val chars: Int,
        val lines: Int,
        val words: Int,
        val tokens: Int
    )

    private val rawFixtures: ByteArray =
        javaClass.getResourceAsStream(RESOURCE)?.readBytes()
            ?: error("Missing test resource $RESOURCE")
    private val fixtures: Fixtures = Gson().fromJson(rawFixtures.toString(Charsets.UTF_8), Fixtures::class.java)

    @Test
    fun `contract fixtures file is byte-identical to the frozen SHA (kept in sync with the VS Code copy)`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(rawFixtures)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            EXPECTED_FIXTURES_SHA,
            sha,
            "Fixtures changed. Regenerate, copy to both repos, and update EXPECTED_FIXTURES_SHA on BOTH sides."
        )
    }

    @Test
    fun `build direction matches the golden wire bytes for every case`() {
        fixtures.buildCases.forEach { case ->
            val options = ClipboardPayloadFormatter.Options(
                headerFormat = case.options.headerFormat,
                preText = case.options.preText,
                postText = case.options.postText,
                addExtraLineBetweenFiles = case.options.addExtraLineBetweenFiles,
                files = case.options.files.map { file ->
                    ClipboardPayloadFormatter.PayloadFile(
                        path = file.path,
                        content = file.content,
                        changeType = file.changeType?.let { ChangeTypeLabel.valueOf(it) },
                        skippedReason = file.skippedReason
                    )
                },
                sourceRoot = case.options.sourceRoot
            )
            val built = when (case.kind) {
                "git" -> ClipboardPayloadFormatter.buildGitPayload(options)
                else -> ClipboardPayloadFormatter.buildPayload(options)
            }
            assertEquals(case.wire, built, "build mismatch: ${case.name}")
        }
    }

    /**
     * Every number in the copy notification — characters, lines, words and the token
     * estimate — must be the SAME in both tools for the same clipboard text. The VS
     * Code mirror (test/contract.test.ts) asserts these exact values against the same
     * frozen file, so a whitespace-class, punctuation-set, line-counting or
     * UTF-16-length drift on either side fails here.
     */
    @Test
    fun `payload statistics match the golden counts for every case`() {
        fixtures.tokenCases.forEach { case ->
            assertEquals(
                TokenEstimator.Stats(case.chars, case.lines, case.words, case.tokens),
                TokenEstimator.stats(case.text),
                "stats mismatch: ${case.name}"
            )
            assertEquals(case.tokens, TokenEstimator.estimate(case.text), "token mismatch: ${case.name}")
        }
    }

    @Test
    fun `parse direction matches the golden entries for every case`() {
        val parser = ClipboardRestoreParser()
        fixtures.parseCases.forEach { case ->
            val actual = parser.parse(case.input, case.headerFormat).map { entry ->
                FxEntry(
                    path = entry.path,
                    content = entry.content,
                    changeTypes = entry.changeTypes.map { it.name }.sorted()
                )
            }
            val expected = case.expected.map { it.copy(changeTypes = it.changeTypes.sorted()) }
            assertEquals(expected, actual, "parse mismatch: ${case.name}")
        }
    }

    /**
     * Both REAL resolvers must send every clipboard path to the same {root, path} — or refuse
     * it for the same reason. The VS Code mirror (test/contract.test.ts) asserts these rows
     * against the same frozen file, on the same layout.
     */
    @Test
    fun `path direction resolves every clipboard path to the frozen cross-tool target`() {
        val layout = fixtures.pathLayout
        val parent = Files.createTempDirectory("clipcode-path-contract").toRealPath()
        try {
            layout.dirs.forEach { parent.resolve(it).createDirectories() }
            layout.files.forEach { (file, text) -> parent.resolve(file).writeText(text) }
            // A directory symlink needs privileges on Windows outside developer mode. Only the
            // rows that depend on it are skipped, and loudly — never the whole table.
            val symlinks = layout.symlinks.all { (link, target) ->
                runCatching { Files.createSymbolicLink(parent.resolve(link), parent.resolve(target)) }.isSuccess
            }
            val roots = layout.roots.map { parent.resolve(it).toString() }
            val resolver = ClipboardPathResolver.fromRootPaths(roots, roots.first())
            fun target(absolutePath: String): String {
                val relative = parent.relativize(Path.of(absolutePath)).map { it.toString() }
                return "${relative.first()}:${relative.drop(1).joinToString("/")}"
            }
            fun expected(outcome: JsonElement): String =
                if (outcome.isJsonPrimitive) outcome.asString
                else outcome.asJsonObject.let { "${it["root"].asString}:${it["path"].asString}" }

            val skipped = mutableListOf<String>()
            val mismatches = fixtures.pathCases.mapNotNull { case ->
                if (case.needsSymlink == true && !symlinks) {
                    skipped += case.input
                    return@mapNotNull null
                }
                val input = case.input.replace("@ROOT@", roots[0]).replace("@SIBLING@", roots[1])
                // A throw is reported as a mismatch for THIS row, so one platform-specific
                // failure does not hide every other row's result.
                val actual = runCatching {
                    val write = when (val r = resolver.resolveWriteTarget(input)) {
                        is ClipboardPathResolver.WriteResolution.Resolved -> target(r.target.absolutePath)
                        is ClipboardPathResolver.WriteResolution.Ambiguous -> "ambiguous"
                        is ClipboardPathResolver.WriteResolution.Unresolved -> "refused"
                    }
                    val delete = when (val r = resolver.resolveDeleteTarget(input)) {
                        is ClipboardPathResolver.DeleteResolution.Resolved -> target(r.target.absolutePath)
                        is ClipboardPathResolver.DeleteResolution.Missing -> "missing"
                        is ClipboardPathResolver.DeleteResolution.Ambiguous -> "ambiguous"
                        is ClipboardPathResolver.DeleteResolution.Unresolved -> "refused"
                    }
                    write to delete
                }.getOrElse { error -> "THROW ${error.javaClass.simpleName}: ${error.message}" to "-" }
                val want = expected(case.write) to expected(case.delete)
                if (actual == want) null else "${Gson().toJson(case.input)}: expected $want, got $actual"
            }
            if (skipped.isNotEmpty()) {
                System.err.println("SKIPPED ${skipped.size} symlink row(s): this platform refused to create a directory symlink")
            }
            assertEquals(emptyList(), mismatches, "path mismatches (write, delete):\n" + mismatches.joinToString("\n"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }

    /**
     * A paste is interoperable only if both tools plan the SAME operations for one payload:
     * which files are created (with what content, over an existing one or not), deleted, or
     * skipped and why. The VS Code mirror (test/contract.test.ts) runs planRestore on the
     * same frozen payloads and layout.
     */
    @Test
    fun `restore direction plans the frozen cross-tool operations for every payload`() {
        val layout = fixtures.restoreLayout
        val parent = Files.createTempDirectory("clipcode-restore-contract").toRealPath()
        try {
            layout.dirs.forEach { parent.resolve(it).createDirectories() }
            layout.files.forEach { (file, spec) ->
                Files.write(parent.resolve(file), spec.base64?.let { java.util.Base64.getDecoder().decode(it) } ?: spec.text.orEmpty().toByteArray())
            }
            // A directory symlink needs privileges on Windows outside developer mode; only the
            // cases that depend on it are skipped, and loudly.
            val symlinks = layout.symlinks.all { (link, target) ->
                runCatching { Files.createSymbolicLink(parent.resolve(link), parent.resolve(target)) }.isSuccess
            }
            val roots = layout.roots.map { parent.resolve(it).toString() }
            val builder = RestorePlanBuilder(ClipboardPathResolver.fromRootPaths(roots, roots.first()))
            val parser = ClipboardRestoreParser()
            fun where(absolutePath: String): Pair<String, String> {
                val relative = parent.relativize(Path.of(absolutePath)).map { it.toString() }
                return relative.first() to relative.drop(1).joinToString("/")
            }
            fun reason(r: RestorePlan.SkipReason) = if (r == RestorePlan.SkipReason.AMBIGUOUS_TARGET) "AMBIGUOUS" else r.name

            val mismatches = fixtures.restoreCases.mapNotNull { case ->
                if (case.needsSymlink == true && !symlinks) {
                    System.err.println("SKIPPED \"${case.name}\": this platform refused to create a directory symlink")
                    return@mapNotNull null
                }
                val actual = runCatching {
                    val plan = builder.build(parser.parse(case.payload, case.headerFormat))
                    Triple(
                        plan.createOperations.map { op ->
                            val (root, path) = where(op.absolutePath)
                            PlannedCreate(root, path, op.relativePath, op.content, op.existed)
                        },
                        plan.deleteOperations.map { op ->
                            val (root, path) = where(op.absolutePath)
                            PlannedDelete(root, path, op.relativePath)
                        },
                        plan.skippedOperations.map { op -> PlannedSkip(op.rawPath, op.relativePath, reason(op.reason)) }
                    )
                }.getOrElse { error -> return@mapNotNull "${case.name}: THROW ${error.javaClass.simpleName}: ${error.message}" }
                val want = Triple(case.creates, case.deletes, case.skips)
                if (actual == want) null else "${case.name}:\n  expected $want\n  got      $actual"
            }
            assertEquals(emptyList(), mismatches, "restore plan mismatches:\n" + mismatches.joinToString("\n"))
        } finally {
            parent.toFile().deleteRecursively()
        }
    }
}
