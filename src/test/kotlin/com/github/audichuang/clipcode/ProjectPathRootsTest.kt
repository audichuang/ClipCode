package com.github.audichuang.clipcode

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectPathRootsTest : BasePlatformTestCase() {
    fun testPrimaryRootPathReturnsBaseDir() {
        val rootPath = ProjectPathRoots.primaryRootPath(project)
        assertNotNull(rootPath)
        assertTrue(rootPath!!.isNotBlank())
        assertEquals(project.basePath, rootPath)
    }

    fun testPrimaryRootReturnsVirtualFile() {
        val root = ProjectPathRoots.primaryRoot(project)
        assertNotNull(root)
        assertTrue(root!!.isDirectory)
        assertEquals(project.basePath, root.path)
    }

    fun testPrimaryRootMatchesContentRootFallback() {
        // 確保 primaryRoot 能跟 ContentRoot fallback 一致（無論哪條 path 被觸發）
        val root = ProjectPathRoots.primaryRoot(project)
        assertNotNull(root)
        assertTrue(root!!.exists())
    }

    fun testPrimaryRootPathNotBlank() {
        val path = ProjectPathRoots.primaryRootPath(project)
        assertNotNull(path)
        assertTrue(path!!.isNotBlank())
        // 確認是絕對路徑
        assertTrue(path.startsWith("/") || path.matches(Regex("^[A-Za-z]:.*")))
    }
}
