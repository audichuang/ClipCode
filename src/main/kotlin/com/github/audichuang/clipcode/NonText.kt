package com.github.audichuang.clipcode

import java.security.MessageDigest

/**
 * Non-text files carried as Base64 (spec: non-text-files.md). Pure logic, no IDE.
 * Mirror of OpenIDEA `crates/ide-clip/src/nontext.rs`.
 */
object NonText {
    /** Base64 characters per line in files mode. */
    const val BASE64_LINE_LEN = 76

    private const val PLACEHOLDER_PREFIX = "// File skipped: "

    // `[0-9]`, never `\d`; matched with matchEntire so `$` line-terminator quirks never apply.
    private val PLACEHOLDER_LINE = Regex(
        "// File skipped: non-text file, ([0-9]+) bytes, sha256 ([0-9a-f]{64}); base64 follows"
    )

    /** Size and hash recorded on a placeholder line. */
    data class Header(val size: Long, val sha256: String)

    enum class Reason { BASE64, SIZE, SHA256 }

    sealed class DecodeResult {
        class Ok(val bytes: ByteArray) : DecodeResult()
        data class Corrupt(val reason: Reason) : DecodeResult()
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The first body line of a files-mode non-text entry. */
    fun placeholderLine(size: Long, sha256Hex: String): String =
        "${PLACEHOLDER_PREFIX}non-text file, $size bytes, sha256 $sha256Hex; base64 follows"

    /** Standard Base64 (RFC 4648 section 4, padded) wrapped at [BASE64_LINE_LEN], lines joined by `\n`. */
    fun encodeWrapped(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes).chunked(BASE64_LINE_LEN).joinToString("\n")

    /** Recognizes the placeholder line (one trailing `\r` is dropped first). */
    fun parsePlaceholderLine(line: String): Header? {
        val l = line.removeSuffix("\r")
        val m = PLACEHOLDER_LINE.matchEntire(l) ?: return null
        val size = m.groupValues[1].toLongOrNull() ?: return null
        return Header(size, m.groupValues[2])
    }

    private fun isAsciiWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'

    private fun b64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    /** Strict RFC 4648 check: padded, alphabet only, padding only at the end, zero trailing bits. */
    private fun isStrictBase64(s: String): Boolean {
        if (s.length % 4 != 0) return false
        var pad = 0
        while (pad < s.length && s[s.length - 1 - pad] == '=') pad++
        if (pad > 2) return false
        val dataLen = s.length - pad
        for (i in 0 until dataLen) if (b64Value(s[i]) < 0) return false
        if (pad > 0) {
            val last = b64Value(s[dataLen - 1])
            val unusedMask = if (pad == 2) 0x0F else 0x03
            if (last and unusedMask != 0) return false
        }
        return true
    }

    /**
     * Strips every ASCII whitespace character, decodes strictly, then checks the length and
     * SHA-256 against [header]. `java.util.Base64` alone accepts missing padding and non-zero
     * trailing bits, so [isStrictBase64] gates it.
     */
    fun decodeVerified(text: String, header: Header): DecodeResult {
        val compact = buildString(text.length) { for (c in text) if (!isAsciiWs(c)) append(c) }
        if (!isStrictBase64(compact)) return DecodeResult.Corrupt(Reason.BASE64)
        val bytes = try {
            java.util.Base64.getDecoder().decode(compact)
        } catch (_: IllegalArgumentException) {
            return DecodeResult.Corrupt(Reason.BASE64)
        }
        if (bytes.size.toLong() != header.size) return DecodeResult.Corrupt(Reason.SIZE)
        if (sha256Hex(bytes) != header.sha256) return DecodeResult.Corrupt(Reason.SHA256)
        return DecodeResult.Ok(bytes)
    }

    /** A parsed (already un-escaped) entry body: null when not a non-text entry. */
    fun parseBody(content: String): DecodeResult? {
        val nl = content.indexOf('\n')
        val first = if (nl < 0) content else content.substring(0, nl)
        val rest = if (nl < 0) "" else content.substring(nl + 1)
        val header = parsePlaceholderLine(first) ?: return null
        return decodeVerified(rest, header)
    }
}
