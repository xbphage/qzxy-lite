package com.qzxy.lite.update

/** GitHub Release APK 资产候选描述（纯数据，便于 JVM 单元测试）。 */
data class ApkAssetCandidate(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

/** 纯 Kotlin 更新资产挑选与弹窗/下载决策逻辑（供 [UpdateManager] 与 `MainActivity` 共用）。 */
object UpdateDecision {

    /**
     * 优先挑选已签名 APK（文件名不含 `unsigned`），若只存在无签名包再降级返回。
     */
    fun selectPreferredApkAsset(assets: List<ApkAssetCandidate>): ApkAssetCandidate? {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank()
        }
        return apks.firstOrNull { !it.name.contains("unsigned", ignoreCase = true) }
            ?: apks.firstOrNull()
    }

    /** 是否应立即复用本地已校验完成的 [cachedVersion] 弹窗提示。 */
    fun shouldShowCachedReady(
        cachedVersion: String,
        dismissedVersion: String?,
        notifyWhenLatest: Boolean,
    ): Boolean = notifyWhenLatest || dismissedVersion != cachedVersion

    /** 远端发现版本 [remoteVersion] 后，是否需要执行静默下载与提示更新。 */
    fun shouldDownloadRemote(
        remoteVersion: String,
        currentVersion: String,
        cachedVersion: String?,
        dismissedVersion: String?,
        notifyWhenLatest: Boolean,
    ): Boolean {
        if (!UpdateManager.isNewer(remoteVersion, currentVersion)) return false
        if (cachedVersion != null && !UpdateManager.isNewer(remoteVersion, cachedVersion)) return false
        return notifyWhenLatest || dismissedVersion != remoteVersion
    }
}
