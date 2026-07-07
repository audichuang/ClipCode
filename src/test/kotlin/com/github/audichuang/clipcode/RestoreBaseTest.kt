package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// Mirror of the VS Code sibling's test/restoreBase.test.ts — keep the two in sync.
class RestoreBaseTest {

    // A fake on-disk tree: the set of directories that exist (absolute paths), and
    // the immediate child dirs of the root.
    private fun probe(existingDirs: List<String>, childDirs: List<String>): DirProbe {
        val set = existingDirs.toSet()
        return object : DirProbe {
            override fun isDir(absPath: String): Boolean = set.contains(absPath.trimEnd('/'))
            override fun childDirs(rootAbsPath: String): List<String> = childDirs
        }
    }

    @Test
    fun `applyRestoreBase adds and strips a leading segment`() {
        assertEquals("repo/src/a.ts", RestoreBaseDetector.applyRestoreBase(RestoreBase.Add("repo"), "src/a.ts"))
        assertEquals("src/a.ts", RestoreBaseDetector.applyRestoreBase(RestoreBase.Strip("repo"), "repo/src/a.ts"))
        // strip only fires when the leading segment matches
        assertEquals("src/a.ts", RestoreBaseDetector.applyRestoreBase(RestoreBase.Strip("repo"), "src/a.ts"))
    }

    @Test
    fun `suggests ADDING a missing wrapper level when files match an existing child dir`() {
        val p = probe(
            listOf("/work/inv-svc-console", "/work/inv-svc-console/src", "/work/inv-svc-console/lib"),
            listOf("inv-svc-console")
        )
        val s = RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts", "src/b.ts", "lib/c.ts"), p)
        assertNotNull(s)
        assertEquals(RestoreBase.Add("inv-svc-console"), s.base)
        assertEquals(3, s.matched)
    }

    @Test
    fun `suggests STRIPPING a redundant wrapper level when the root is already inside it`() {
        val p = probe(listOf("/work/inv-svc-console/src", "/work/inv-svc-console/lib"), listOf("src", "lib"))
        val s = RestoreBaseDetector.suggestRestoreBase(
            "/work/inv-svc-console",
            listOf("inv-svc-console/src/a.ts", "inv-svc-console/lib/c.ts"),
            p
        )
        assertNotNull(s)
        assertEquals(RestoreBase.Strip("inv-svc-console"), s.base)
    }

    @Test
    fun `no suggestion when the paths already align with the project`() {
        val p = probe(listOf("/work/src", "/work/lib"), listOf("src", "lib"))
        assertNull(RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts", "lib/c.ts"), p))
    }

    @Test
    fun `no suggestion when nothing matches any interpretation`() {
        val p = probe(emptyList(), emptyList())
        assertNull(RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts", "lib/c.ts"), p))
    }

    @Test
    fun `no suggestion from root-level-only paths`() {
        val p = probe(listOf("/work/inv-svc-console"), listOf("inv-svc-console"))
        assertNull(RestoreBaseDetector.suggestRestoreBase("/work", listOf("a.ts", "README.md"), p))
    }

    @Test
    fun `metadata nests under the named source repo when that folder exists here`() {
        val p = probe(listOf("/work/inv-svc-console"), listOf("inv-svc-console", "other"))
        val s = RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts", "lib/b.ts"), p, "inv-svc-console")
        assertEquals(RestoreBase.Add("inv-svc-console"), s?.base)
    }

    @Test
    fun `metadata works for a single file and for root-level-only paths`() {
        val p = probe(listOf("/work/inv-svc-console"), listOf("inv-svc-console"))
        assertEquals(
            RestoreBase.Add("inv-svc-console"),
            RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts"), p, "inv-svc-console")?.base
        )
        assertEquals(
            RestoreBase.Add("inv-svc-console"),
            RestoreBaseDetector.suggestRestoreBase("/work", listOf("README.md"), p, "inv-svc-console")?.base
        )
    }

    @Test
    fun `metadata strips the redundant repo wrapper when the project IS that repo`() {
        val p = probe(emptyList(), emptyList())
        val s = RestoreBaseDetector.suggestRestoreBase(
            "/work/inv-svc-console",
            listOf("inv-svc-console/src/a.ts", "inv-svc-console/lib/b.ts"),
            p,
            "work"
        )
        assertEquals(RestoreBase.Strip("inv-svc-console"), s?.base)
    }

    @Test
    fun `metadata no suggestion when the project is the same-named root`() {
        val p = probe(listOf("/work/inv-svc-console/src"), listOf("src"))
        assertNull(
            RestoreBaseDetector.suggestRestoreBase("/work/inv-svc-console", listOf("src/a.ts", "lib/b.ts"), p, "inv-svc-console")
        )
    }

    @Test
    fun `metadata no guess when the named repo folder is not present`() {
        val p = probe(listOf("/work/bar/src"), listOf("src"))
        assertNull(RestoreBaseDetector.suggestRestoreBase("/work/bar", listOf("src/a.ts", "lib/b.ts"), p, "foo"))
    }

    @Test
    fun `no suggestion when two child dirs match equally well`() {
        val p = probe(
            listOf("/work/repo-a", "/work/repo-a/src", "/work/repo-b", "/work/repo-b/src"),
            listOf("repo-a", "repo-b")
        )
        assertNull(RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts", "src/b.ts"), p))
    }

    @Test
    fun `does not strip a legitimately-named top folder that is not the project name`() {
        val p = probe(listOf("/work/src", "/work/lib"), listOf("src", "lib"))
        assertNull(RestoreBaseDetector.suggestRestoreBase("/work", listOf("examples/src/a.ts", "examples/lib/b.ts"), p))
    }

    @Test
    fun `no suggestion from a single subdir-bearing path`() {
        val p = probe(listOf("/work/inv-svc-console", "/work/inv-svc-console/src"), listOf("inv-svc-console"))
        assertNull(RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts"), p))
    }

    @Test
    fun `requires a majority match not a single coincidental hit`() {
        val p = probe(listOf("/work/inv-svc-console", "/work/inv-svc-console/src"), listOf("inv-svc-console"))
        assertNull(
            RestoreBaseDetector.suggestRestoreBase("/work", listOf("src/a.ts", "lib/b.ts", "app/c.ts", "web/d.ts"), p)
        )
    }

    @Test
    fun `absolute paths are excluded from alignment`() {
        assertEquals(false, RestoreBaseDetector.isRelativeEntryPath("/abs/path.kt"))
        assertEquals(false, RestoreBaseDetector.isRelativeEntryPath("C:/abs/path.kt"))
        assertEquals(false, RestoreBaseDetector.isRelativeEntryPath("\\\\server/share.kt"))
        assertEquals(true, RestoreBaseDetector.isRelativeEntryPath("src/a.kt"))
    }
}
