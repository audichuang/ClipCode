package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortableNameTest {
    private val portable = listOf(
        "a.txt",
        "dir/a b.txt",
        "café/😀.png",
        "del\u007F.txt",
        "trail /x.txt",
        "a\u0085b.txt",
    )

    private val notPortable = listOf(
        "back\\slash.txt",
        "tab\tname.txt",
        "a\nfile: [DELETED] victim.txt",
        "cr\rname.txt",
        "nul\u0000.bin",
        "unit\u001Fsep.txt",
        "q\"uote.txt",
        "a:b.txt",
        "lt<.txt", "gt>.txt", "pipe|.txt", "what?.txt", "star*.txt",
        "x.txt ",
        " x.txt",
        "x.txt\u000B",
        "../x.txt",
        "a/../x.txt",
        "./x.txt",
        "a//b.txt",
        "/abs.txt",
        "",
    )

    @Test
    fun `portable names are exactly the paths the reader keeps unchanged`() {
        portable.forEach { assertTrue(PortableName.isPortableRelativePath(it), "should be portable: $it") }
        notPortable.forEach { assertFalse(PortableName.isPortableRelativePath(it), "should not be portable: $it") }
        // A name the writer emits is one the reader maps back to the very same path.
        val resolver = ClipboardPathResolver.fromRootPaths(listOf("/r"))
        (portable + notPortable).filter { it.isNotEmpty() }.forEach { p ->
            assertEquals(
                PortableName.isPortableRelativePath(p),
                resolver.toClipboardPath("/r/$p") == p,
                "writer and reader disagree on: $p"
            )
        }
    }

    @Test
    fun `a folder is checked by its segments, not by trailing whitespace`() {
        assertTrue(PortableName.isPortableFolderPath(""))
        assertTrue(PortableName.isPortableFolderPath("trail "))
        assertTrue(PortableName.isPortableFolderPath("a/trail "))
        assertFalse(PortableName.isPortableFolderPath("back\\slash"))
        assertFalse(PortableName.isPortableFolderPath("a/tab\tdir"))
        assertFalse(PortableName.isPortableFolderPath(" lead"))
    }

    @Test
    fun `checked path is the raw root relative path, or the absolute path without its root`() {
        val resolver = ClipboardPathResolver.fromRootPaths(listOf("/r"))
        assertEquals("odd/back\\slash.txt", resolver.rawRelativePath("/r/odd/back\\slash.txt"))
        assertEquals("odd/x.txt ", resolver.rawRelativePath("/r/odd/x.txt "))
        assertEquals("odd/tab\tname.txt", resolver.rawRelativePath("/r/odd/tab\tname.txt"))
        assertEquals("", resolver.rawRelativePath("/r"))
        assertNull(resolver.rawRelativePath("/elsewhere/x.txt"))
        assertEquals("odd/back\\slash.txt", PortableName.checkedPath(resolver, "/r/odd/back\\slash.txt"))
        assertEquals("a\nb.txt", PortableName.checkedPath(resolver, "/elsewhere/a\nb.txt"))
        assertEquals("x.txt", PortableName.checkedPath(resolver, "C:/Users/x.txt"))
    }

    /**
     * B2: a file under no copy root is judged by its own name (the part that becomes the relative path),
     * never by absolute ancestors a reader never sees as segments: a UNC or GVFS mount stays copyable.
     * The absolute path still may not hold a control character, or the header would not be one line.
     */
    @Test
    fun `a file outside every root is judged by its own name`() {
        val resolver = ClipboardPathResolver.fromRootPaths(listOf("/proj"))
        assertTrue(PortableName.isPortableFile(resolver, "//server/share/notes.txt"))
        assertTrue(PortableName.isPortableFile(resolver, "/run/user/1000/gvfs/smb-share:server=x,share=y/a.txt"))
        assertTrue(PortableName.isPortableFile(resolver, "C:/Users/x.txt"))
        assertTrue(PortableName.isPortableFolder(resolver, "/run/user/1000/gvfs/smb-share:server=x,share=y/sub"))
        assertFalse(PortableName.isPortableFile(resolver, "/elsewhere/back\\slash.txt"))
        assertFalse(PortableName.isPortableFile(resolver, "/elsewhere/a\nb.txt"))
        assertFalse(PortableName.isPortableFile(resolver, "/tmp/x\nfile: [DELETED] victim.txt/notes.txt"))
        assertFalse(PortableName.isPortableFolder(resolver, "/tmp/x\ny/sub"))
        // Under a root nothing changes: the whole relative path is checked.
        assertFalse(PortableName.isPortableFile(resolver, "/proj/a:b/x.txt"))
        assertTrue(PortableName.isPortableFile(resolver, "/proj/a/x.txt"))
        assertFalse(PortableName.isPortableFolder(resolver, "/proj/tab\tdir"))
    }

    @Test
    fun `control characters are made visible in a notice`() {
        assertEquals("tab\\tname.txt", PortableName.visibleName("tab\tname.txt"))
        assertEquals("a\\x0afile.txt", PortableName.visibleName("a\nfile.txt"))
        assertEquals("back\\slash.txt", PortableName.visibleName("back\\slash.txt"))
    }
}
