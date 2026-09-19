package com.github.audichuang.clipcode

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Strict UTF-8 or nothing. Byte-mirror of `ClipCodeVSCode/src/fileSystem.ts
 * decodeUtf8OrSkip`.
 *
 * Decoding a Big5 / Shift_JIS / UTF-16 file with the project charset produces correct text
 * HERE and nothing that can round-trip: the clipboard format carries no encoding, so the
 * other tool writes it back as UTF-8 and the original bytes are gone. Copying such a file
 * therefore looks like a feature and is a trap the moment the payload crosses tools. Both
 * sides now skip it — visibly, with a count — rather than emit something that cannot come
 * back. A NUL byte means binary, same as before.
 */
object Utf8Text {
    /**
     * 8 MiB. A restore target larger than this is not something a clipboard payload should
     * be replacing unasked, and reading it whole just to inspect its encoding costs more
     * than the check is worth — so it is reported as unverifiable rather than read.
     * Mirror of fileSystem.ts ENCODING_CHECK_LIMIT.
     */
    private const val ENCODING_CHECK_LIMIT = 8L * 1024 * 1024

    enum class TargetEncoding { ABSENT, UTF8, OTHER, UNVERIFIABLE }

    /**
     * What is on disk at [absolutePath] right now. Fails CLOSED: a file we could not read,
     * or one too large to read, is UNVERIFIABLE and must not be overwritten either.
     * Treating a failed read as "not non-UTF-8" authorised overwriting exactly the files
     * we could say least about. Mirror of fileSystem.ts targetEncoding.
     */
    fun targetEncoding(absolutePath: String): TargetEncoding {
        val path = runCatching { java.nio.file.Path.of(absolutePath) }.getOrNull() ?: return TargetEncoding.ABSENT
        if (!java.nio.file.Files.isRegularFile(path)) return TargetEncoding.ABSENT
        val size = runCatching { java.nio.file.Files.size(path) }.getOrNull() ?: return TargetEncoding.UNVERIFIABLE
        if (size > ENCODING_CHECK_LIMIT) return TargetEncoding.UNVERIFIABLE
        val bytes = runCatching { java.nio.file.Files.readAllBytes(path) }.getOrNull()
            ?: return TargetEncoding.UNVERIFIABLE
        return if (decodeOrNull(bytes) == null) TargetEncoding.OTHER else TargetEncoding.UTF8
    }

    /** True when the bytes on disk must not be replaced with UTF-8 text. */
    fun mustNotOverwrite(absolutePath: String): Boolean =
        targetEncoding(absolutePath).let { it == TargetEncoding.OTHER || it == TargetEncoding.UNVERIFIABLE }

    /**
     * The only trace a lenient decode leaves behind once the bytes are gone. Used where an
     * API hands back a String and no ByteArray — a file that genuinely contains U+FFFD is
     * rare, and refusing it is the safe side of putting mojibake on the clipboard.
     */
    fun looksLikeMojibake(text: String): Boolean = text.contains('\uFFFD')

    fun decodeOrNull(bytes: ByteArray): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }.getOrNull()
    }
}
