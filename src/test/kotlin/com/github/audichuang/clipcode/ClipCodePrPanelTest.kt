package com.github.audichuang.clipcode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClipCodePrPanelTest {
    @Test
    fun `no upstream shows local-only message and hides fetch button`() {
        val banner = ClipCodePrPanel.formatRemoteBanner(
            BranchDiffProvider.RemoteStatus(ahead = 0, behind = 0, upstream = null, fetched = false, fetchAttempted = true)
        )
        assertEquals("本分支無對應 origin 分支", banner.text)
        assertFalse(banner.showFetchButton)
    }

    @Test
    fun `behind shows pull warning with count and fetch button`() {
        val banner = ClipCodePrPanel.formatRemoteBanner(
            BranchDiffProvider.RemoteStatus(ahead = 1, behind = 3, upstream = "origin/main", fetched = true, fetchAttempted = true)
        )
        assertTrue(banner.text.contains("3"))
        assertTrue(banner.text.contains("pull"))
        assertTrue(banner.showFetchButton)
    }

    @Test
    fun `synced shows upstream and ahead count`() {
        val banner = ClipCodePrPanel.formatRemoteBanner(
            BranchDiffProvider.RemoteStatus(ahead = 2, behind = 0, upstream = "origin/main", fetched = true, fetchAttempted = true)
        )
        assertTrue(banner.text.contains("origin/main"))
        assertTrue(banner.text.contains("2"))
        assertTrue(banner.showFetchButton)
    }

    @Test
    fun `fetch failure appends offline note`() {
        val behindBanner = ClipCodePrPanel.formatRemoteBanner(
            BranchDiffProvider.RemoteStatus(ahead = 0, behind = 1, upstream = "origin/main", fetched = false, fetchAttempted = true)
        )
        assertTrue(behindBanner.text.contains("未能連線"))

        val syncedBanner = ClipCodePrPanel.formatRemoteBanner(
            BranchDiffProvider.RemoteStatus(ahead = 0, behind = 0, upstream = "origin/main", fetched = false, fetchAttempted = true)
        )
        assertTrue(syncedBanner.text.contains("未能連線"))
    }

    @Test
    fun `no offline note when fetch not attempted`() {
        // base-combo change passes doFetch=false → fetchAttempted=false → 不可誤報離線
        val syncedBanner = ClipCodePrPanel.formatRemoteBanner(
            BranchDiffProvider.RemoteStatus(ahead = 0, behind = 0, upstream = "origin/main", fetched = false, fetchAttempted = false)
        )
        assertFalse(syncedBanner.text.contains("未能連線"))

        val behindBanner = ClipCodePrPanel.formatRemoteBanner(
            BranchDiffProvider.RemoteStatus(ahead = 0, behind = 2, upstream = "origin/main", fetched = false, fetchAttempted = false)
        )
        assertFalse(behindBanner.text.contains("未能連線"))
    }
}
