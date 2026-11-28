package com.qzxy.lite

import com.qzxy.lite.update.UpdateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun `buildCandidateUrls prioritizes gh-proxy before fallback mirror and direct github`() {
        val raw = "https://github.com/xbphage/qzxy-lite/releases/download/v1.0.3/app.apk"
        assertEquals(
            listOf(
                "https://gh-proxy.com/$raw",
                "https://ghproxy.net/$raw",
                raw,
            ),
            UpdateManager.buildCandidateUrls(raw),
        )
    }

    @Test
    fun `buildCandidateUrls strips existing proxy prefix to avoid double-proxying`() {
        val rawApi = "https://api.github.com/repos/xbphage/qzxy-lite/releases/latest"
        assertEquals(
            listOf(
                "https://gh-proxy.com/$rawApi",
                "https://ghproxy.net/$rawApi",
                rawApi,
            ),
            UpdateManager.buildCandidateUrls("https://gh-proxy.com/$rawApi"),
        )
    }

    @Test
    fun `matchingSignerDigests rejects unsigned and mismatched certs while allowing matching certs`() {
        val installed = setOf("aabbccdd")
        assertTrue(UpdateManager.matchingSignerDigests(setOf("aabbccdd"), installed))
        assertFalse(UpdateManager.matchingSignerDigests(emptySet(), installed))
        assertFalse(UpdateManager.matchingSignerDigests(setOf("11223344"), installed))
        assertTrue(UpdateManager.isNewer("v1.0.3", "1.0.2"))
        assertFalse(UpdateManager.isNewer("v1.0.3", "1.0.3"))
    }
}
