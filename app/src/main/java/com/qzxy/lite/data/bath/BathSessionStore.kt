package com.qzxy.lite.data.bath

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 洗澡阶段。
 *
 * 原先定义在 `ui.bath`，但"设备在不在用水"是服务端事实、不是某个页面的瞬时态，
 * 页面出栈重建后仍然成立，所以随快照一起下沉到数据层。
 */
enum class BathPhase { IDLE, OPENING, SHOWERING, CLOSING, CLOSED }

/** 单台设备的洗澡快照（不含 busy 这类纯 UI 瞬时态）。 */
data class BathSnapshot(
    val phase: BathPhase = BathPhase.IDLE,
    val orderNo: String? = null,
    val message: String? = null,
    val isBleMode: Boolean = false,
    val updatedAt: Long = 0L,
)

/**
 * 进程级洗澡会话存储。
 *
 * 场景：开阀后返回首页，`bath` 导航条目出栈 → BathViewModel 被 clear → 再进入是全新实例，
 * 状态回到 IDLE（"待机"），而设备其实还在出水。
 *
 * 两层保障：
 * 1. 本存储跨 ViewModel 存活（AppContainer 持有），页面重建先读这里，不闪回待机；
 * 2. ViewModel 进入时再向服务端 `query/rateOrder/using` 对账（307=有进行中订单），
 *    本地只是缓存，服务端才是真相——所以换设备/被原版 App 关阀也能纠正。
 */
class BathSessionStore {

    private val _snapshots = MutableStateFlow<Map<String, BathSnapshot>>(emptyMap())
    val snapshots: StateFlow<Map<String, BathSnapshot>> = _snapshots.asStateFlow()

    fun get(sn: String): BathSnapshot? = _snapshots.value[sn]

    fun put(sn: String, snapshot: BathSnapshot) {
        val stamped = snapshot.copy(updatedAt = System.currentTimeMillis())
        _snapshots.update { it + (sn to stamped) }
    }

    fun remove(sn: String) {
        _snapshots.update { it - sn }
    }

    fun clear() {
        _snapshots.value = emptyMap()
    }
}
