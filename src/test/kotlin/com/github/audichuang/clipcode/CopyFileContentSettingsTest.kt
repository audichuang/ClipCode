package com.github.audichuang.clipcode

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CopyFileContentSettingsTest : BasePlatformTestCase() {
    fun testDefaultState() {
        val state = CopyFileContentSettings.State()
        assertEquals("// file: \$FILE_PATH", state.headerFormat)
        assertEquals("", state.preText)
        assertEquals("", state.postText)
        assertEquals(30, state.fileCountLimit)
        assertEquals(500, state.maxFileSizeKB)
        assertEquals(2048, state.maxNonTextFileSizeKB)
        assertTrue(state.addExtraLineBetweenFiles)
        assertTrue(state.setMaxFileCount)
        assertTrue(state.showCopyNotification)
        assertEquals(0, state.filterRules.size)
        assertTrue(!state.useFilters)
        assertTrue(state.useIncludeFilters)
        assertTrue(state.useExcludeFilters)
    }

    fun testLoadStateOverwritesPrevious() {
        val settings = CopyFileContentSettings.getInstance(project)
        assertNotNull(settings)
        val newState = CopyFileContentSettings.State(
            headerFormat = "==> \$FILE_PATH",
            preText = "PRE",
            postText = "POST",
            fileCountLimit = 99,
            maxFileSizeKB = 1024,
            maxNonTextFileSizeKB = 10000,
            addExtraLineBetweenFiles = false,
            setMaxFileCount = false,
            showCopyNotification = false,
            useFilters = true,
            useIncludeFilters = false,
            useExcludeFilters = false
        )
        settings!!.loadState(newState)

        val got = settings.state
        assertEquals("==> \$FILE_PATH", got.headerFormat)
        assertEquals("PRE", got.preText)
        assertEquals("POST", got.postText)
        assertEquals(99, got.fileCountLimit)
        assertEquals(1024, got.maxFileSizeKB)
        assertEquals(10000, got.maxNonTextFileSizeKB)
        assertTrue(!got.addExtraLineBetweenFiles)
        assertTrue(!got.setMaxFileCount)
        assertTrue(!got.showCopyNotification)
        assertTrue(got.useFilters)
        assertTrue(!got.useIncludeFilters)
        assertTrue(!got.useExcludeFilters)
    }

    fun testFilterRuleDefaults() {
        val rule = CopyFileContentSettings.FilterRule()
        assertEquals(CopyFileContentSettings.FilterType.PATH, rule.type)
        assertEquals(CopyFileContentSettings.FilterAction.INCLUDE, rule.action)
        assertEquals("", rule.value)
        assertTrue(rule.enabled)
    }

    fun testFilterRuleCustomConstruction() {
        val rule = CopyFileContentSettings.FilterRule(
            type = CopyFileContentSettings.FilterType.PATTERN,
            action = CopyFileContentSettings.FilterAction.EXCLUDE,
            value = "*.tmp",
            enabled = false
        )
        assertEquals(CopyFileContentSettings.FilterType.PATTERN, rule.type)
        assertEquals(CopyFileContentSettings.FilterAction.EXCLUDE, rule.action)
        assertEquals("*.tmp", rule.value)
        assertTrue(!rule.enabled)
    }

    fun testFilterTypeEnumValues() {
        val types = CopyFileContentSettings.FilterType.entries
        assertEquals(2, types.size)
        assertEquals(CopyFileContentSettings.FilterType.PATH, CopyFileContentSettings.FilterType.valueOf("PATH"))
        assertEquals(CopyFileContentSettings.FilterType.PATTERN, CopyFileContentSettings.FilterType.valueOf("PATTERN"))
    }

    fun testFilterActionEnumValues() {
        val actions = CopyFileContentSettings.FilterAction.entries
        assertEquals(2, actions.size)
        assertEquals(CopyFileContentSettings.FilterAction.INCLUDE, CopyFileContentSettings.FilterAction.valueOf("INCLUDE"))
        assertEquals(CopyFileContentSettings.FilterAction.EXCLUDE, CopyFileContentSettings.FilterAction.valueOf("EXCLUDE"))
    }

    fun testGetInstanceReturnsNonNullForRealProject() {
        val settings = CopyFileContentSettings.getInstance(project)
        assertNotNull(settings)
    }

    fun testMaxNonTextFileSizePersistsThroughSerialization() {
        val state = CopyFileContentSettings.State(maxNonTextFileSizeKB = 4096)
        val element = com.intellij.util.xmlb.XmlSerializer.serialize(state)
        val back = com.intellij.util.xmlb.XmlSerializer.deserialize(element, CopyFileContentSettings.State::class.java)
        assertEquals(4096, back.maxNonTextFileSizeKB)
    }
}
