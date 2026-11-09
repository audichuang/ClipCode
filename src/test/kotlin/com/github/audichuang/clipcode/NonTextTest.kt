package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NonTextTest {
    private val bytes = byteArrayOf(0, 0xff.toByte(), 0x10, 0x20)
    private val header = NonText.Header(4, NonText.sha256Hex(bytes))
    private val b64 = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun ok(r: NonText.DecodeResult): ByteArray = (r as NonText.DecodeResult.Ok).bytes
    private fun corrupt(r: NonText.DecodeResult): NonText.Reason = (r as NonText.DecodeResult.Corrupt).reason

    @Test
    fun `placeholder line round trips and is exact`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2)
        val sha = NonText.sha256Hex(png)
        val line = NonText.placeholderLine(7, sha)
        assertEquals("// File skipped: non-text file, 7 bytes, sha256 $sha; base64 follows", line)
        assertEquals(NonText.Header(7, sha), NonText.parsePlaceholderLine(line))
        assertNotNull(NonText.parsePlaceholderLine("$line\r"))
        assertNull(NonText.parsePlaceholderLine(" $line"))
        assertNull(NonText.parsePlaceholderLine(line.uppercase()))
        assertNull(NonText.parsePlaceholderLine(line.replace(" 7 ", " ٧ ")))
        assertNull(NonText.parsePlaceholderLine("// File skipped: size exceeds limit (9 bytes)"))
    }

    @Test
    fun `wrapping is 76 chars per line joined by newline`() {
        for (n in listOf(1, 2, 3, 56, 57, 58, 114, 1000)) {
            val data = ByteArray(n) { (it * 7).toByte() }
            val wrapped = NonText.encodeWrapped(data)
            val lines = wrapped.split("\n")
            assertTrue(lines.all { it.length in 1..76 })
            assertTrue(lines.dropLast(1).all { it.length == 76 })
            assertContentEquals(data, java.util.Base64.getDecoder().decode(wrapped.replace("\n", "")))
        }
    }

    @Test
    fun `decode verified accepts clean and whitespace-laden input`() {
        assertContentEquals(bytes, ok(NonText.decodeVerified(b64, header)))
        val messy = " ${b64.take(3)}\r\n\u000C${b64.drop(3)}\t\n"
        assertContentEquals(bytes, ok(NonText.decodeVerified(messy, header)))
    }

    /** The stripped set is Rust's is_ascii_whitespace (OpenIDEA) and the VS Code port's: VT is not in it. */
    @Test
    fun `decode verified rejects vertical tab inside base64`() {
        val withVt = "${b64.take(4)}\u000B${b64.drop(4)}"
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified(withVt, header)))
        val withFf = "${b64.take(4)}\u000C${b64.drop(4)}"
        assertContentEquals(bytes, ok(NonText.decodeVerified(withFf, header)))
    }

    @Test
    fun `decode verified rejects missing padding and truncation`() {
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified(b64.replace("=", ""), header)))
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified(b64.dropLast(1), header)))
    }

    @Test
    fun `decode verified rejects non-zero trailing bits`() {
        val one = byteArrayOf(0)
        val h = NonText.Header(1, NonText.sha256Hex(one))
        assertContentEquals(one, ok(NonText.decodeVerified("AA==", h)))
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified("AB==", h)))
        val two = byteArrayOf(0, 0)
        val h2 = NonText.Header(2, NonText.sha256Hex(two))
        assertContentEquals(two, ok(NonText.decodeVerified("AAA=", h2)))
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified("AAB=", h2)))
    }

    @Test
    fun `decode verified rejects characters outside the alphabet and misplaced padding`() {
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified("AA-_", header)))
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified("AAéA", header)))
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified("A=AA", header)))
        assertEquals(NonText.Reason.BASE64, corrupt(NonText.decodeVerified("A===", header)))
    }

    @Test
    fun `decode verified reports wrong size and wrong sha`() {
        assertEquals(NonText.Reason.SIZE, corrupt(NonText.decodeVerified(java.util.Base64.getEncoder().encodeToString(bytes.copyOf(3)), header)))
        assertEquals(NonText.Reason.SHA256, corrupt(NonText.decodeVerified(b64, header.copy(sha256 = "0".repeat(64)))))
    }

    @Test
    fun `parse body reads the placeholder then base64 with CRLF`() {
        val body = NonText.placeholderLine(4, header.sha256) + "\r\n" + b64
        assertContentEquals(bytes, ok(NonText.parseBody(body)!!))
        assertNull(NonText.parseBody("plain text\n$b64"))
    }
}
