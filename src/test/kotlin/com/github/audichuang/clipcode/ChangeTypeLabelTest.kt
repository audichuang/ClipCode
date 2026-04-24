package com.github.audichuang.clipcode

import com.intellij.openapi.vcs.changes.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChangeTypeLabelTest {
    @Test
    fun `fromChangeType maps all four enum values`() {
        assertEquals(ChangeTypeLabel.NEW, ChangeTypeLabel.fromChangeType(Change.Type.NEW))
        assertEquals(ChangeTypeLabel.MODIFIED, ChangeTypeLabel.fromChangeType(Change.Type.MODIFICATION))
        assertEquals(ChangeTypeLabel.DELETED, ChangeTypeLabel.fromChangeType(Change.Type.DELETED))
        assertEquals(ChangeTypeLabel.MOVED, ChangeTypeLabel.fromChangeType(Change.Type.MOVED))
    }

    @Test
    fun `fromLabel is case insensitive and returns null for unknown`() {
        assertEquals(ChangeTypeLabel.DELETED, ChangeTypeLabel.fromLabel("[deleted]"))
        assertEquals(ChangeTypeLabel.MODIFIED, ChangeTypeLabel.fromLabel("[Modified]"))
        assertNull(ChangeTypeLabel.fromLabel("[UNKNOWN]"))
    }

    @Test
    fun `extractLeadingLabels returns empty set for blank path`() {
        assertEquals(emptySet(), ChangeTypeLabel.extractLeadingLabels(""))
        assertEquals(emptySet(), ChangeTypeLabel.extractLeadingLabels("   "))
    }

    @Test
    fun `extractLeadingLabels reads multiple prefix labels only`() {
        val labels = ChangeTypeLabel.extractLeadingLabels("[MODIFIED] [DELETED] src/main/App.kt")

        assertEquals(setOf(ChangeTypeLabel.MODIFIED, ChangeTypeLabel.DELETED), labels)
    }

    @Test
    fun `isDeleted only matches leading deleted label`() {
        assertTrue(ChangeTypeLabel.isDeleted("[DELETED] src/main/App.kt"))
        assertFalse(ChangeTypeLabel.isDeleted("src/[DELETED]/App.kt"))
    }

    @Test
    fun `stripLabels removes only leading labels`() {
        assertEquals(
            "src/[DELETED]/App.kt",
            ChangeTypeLabel.stripLabels("[NEW] src/[DELETED]/App.kt")
        )
    }
}
