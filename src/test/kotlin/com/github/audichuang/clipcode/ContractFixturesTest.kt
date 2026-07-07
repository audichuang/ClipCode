package com.github.audichuang.clipcode

import com.google.gson.Gson
import java.security.MessageDigest
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
        const val EXPECTED_FIXTURES_SHA = "aa5010128ab8eb2507c7f32aefb878c6cc786aa411ef9e8898e06ddaeee5179b"
        const val RESOURCE = "/clipboard-contract.json"
    }

    private data class Fixtures(val buildCases: List<BuildCase>, val parseCases: List<ParseCase>)
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
}
