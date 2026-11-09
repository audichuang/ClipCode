package com.github.audichuang.clipcode

import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The write window around a read-only target must put back exactly the mode the file had. A fake
 * store stands in for Windows NTFS, which no POSIX CI runner can reproduce: a file whose ACL denies
 * writing is not writable although its DOS read-only attribute is clear.
 */
class WithWritablePathTest {
    private class FakeStore(
        var writable: Boolean,
        var posix: Set<PosixFilePermission>?,
        var readOnly: Boolean?
    ) : FileWriteAccess {
        val calls = mutableListOf<String>()
        override fun isWritable(path: Path) = writable
        override fun posixPermissions(path: Path) = posix
        override fun setPosixPermissions(path: Path, permissions: Set<PosixFilePermission>) {
            calls += "posix=$permissions"; posix = permissions
        }
        override fun readOnlyAttribute(path: Path) = readOnly
        override fun setReadOnlyAttribute(path: Path, readOnly: Boolean) {
            calls += "readOnly=$readOnly"; this.readOnly = readOnly
        }
    }

    private val path = Path.of("t.bin")

    @Test
    fun aclDeniedFileWithoutReadOnlyAttributeDoesNotGainOneWhenTheWriteFails() {
        val store = FakeStore(writable = false, posix = null, readOnly = false)
        assertFailsWith<java.nio.file.AccessDeniedException> {
            withWritablePath(path, store, {}) { throw java.nio.file.AccessDeniedException("t.bin") }
        }
        assertEquals(false, store.readOnly)
        assertEquals(emptyList(), store.calls)
    }

    @Test
    fun aclDeniedFileWithoutReadOnlyAttributeKeepsItAfterASuccessfulWrite() {
        val store = FakeStore(writable = false, posix = null, readOnly = false)
        assertEquals(1, withWritablePath(path, store, {}) { 1 })
        assertEquals(false, store.readOnly)
        assertEquals(emptyList(), store.calls)
    }

    @Test
    fun readOnlyAttributeIsClearedForTheWriteAndPutBack() {
        val store = FakeStore(writable = false, posix = null, readOnly = true)
        var seenDuringWrite: Boolean? = null
        withWritablePath(path, store, {}) { seenDuringWrite = store.readOnly }
        assertEquals(false, seenDuringWrite)
        assertEquals(true, store.readOnly)
        assertEquals(listOf("readOnly=false", "readOnly=true"), store.calls)
    }

    @Test
    fun readOnlyAttributeIsPutBackWhenTheWriteFails() {
        val store = FakeStore(writable = false, posix = null, readOnly = true)
        assertFailsWith<IllegalStateException> { withWritablePath(path, store, {}) { error("boom") } }
        assertEquals(true, store.readOnly)
    }

    @Test
    fun storeWithNeitherViewIsWrittenWithoutTouchingAnyMode() {
        val store = FakeStore(writable = false, posix = null, readOnly = null)
        assertEquals(2, withWritablePath(path, store, {}) { 2 })
        assertEquals(emptyList(), store.calls)
    }

    @Test
    fun posixModeIsRestoredExactly() {
        val mode = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ)
        val store = FakeStore(writable = false, posix = mode, readOnly = null)
        assertFailsWith<IllegalStateException> { withWritablePath(path, store, {}) { error("boom") } }
        assertEquals(mode, store.posix)
    }
}
