package com.qzxy.lite

import com.qzxy.lite.update.ApkAssetCandidate
import com.qzxy.lite.update.UpdateDecision
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

    @Test
    fun `selectPreferredApkAsset prefers signed apk over unsigned asset`() {
        val unsignedFirst = listOf(
            ApkAssetCandidate("qzxy-lite-v1.0.6-unsigned.apk", "https://example.com/u.apk", 100L),
            ApkAssetCandidate("qzxy-lite-v1.0.6.apk", "https://example.com/s.apk", 120L),
        )
        assertEquals("https://example.com/s.apk", UpdateDecision.selectPreferredApkAsset(unsignedFirst)?.downloadUrl)

        val onlyUnsigned = listOf(
            ApkAssetCandidate("qzxy-lite-v1.0.6-unsigned.apk", "https://example.com/u.apk", 100L),
        )
        assertEquals("https://example.com/u.apk", UpdateDecision.selectPreferredApkAsset(onlyUnsigned)?.downloadUrl)
    }

    @Test
    fun `UpdateDecision allows discovering higher release even after caching an older update`() {
        // 缓存了 1.0.5 且尚未点击稍后：启动先展示缓存包，同时若远端出现 1.0.6 也允许继续下载升级
        assertTrue(UpdateDecision.shouldShowCachedReady("v1.0.5", dismissedVersion = null, notifyWhenLatest = false))
        assertTrue(
            UpdateDecision.shouldDownloadRemote(
                remoteVersion = "v1.0.6",
                currentVersion = "1.0.4",
                cachedVersion = "v1.0.5",
                dismissedVersion = "v1.0.5",
                notifyWhenLatest = false,
            ),
        )
        // 若远端仍为 1.0.5（未出更高版），不重复下载
        assertFalse(
            UpdateDecision.shouldDownloadRemote(
                remoteVersion = "v1.0.5",
                currentVersion = "1.0.4",
                cachedVersion = "v1.0.5",
                dismissedVersion = null,
                notifyWhenLatest = false,
            ),
        )
        // 同会话点击“稍后”忽略 1.0.5 时，自动检查不再弹 1.0.5，手动点击检查仍提示
        assertFalse(UpdateDecision.shouldShowCachedReady("v1.0.5", dismissedVersion = "v1.0.5", notifyWhenLatest = false))
        assertTrue(UpdateDecision.shouldShowCachedReady("v1.0.5", dismissedVersion = "v1.0.5", notifyWhenLatest = true))
    }
}
