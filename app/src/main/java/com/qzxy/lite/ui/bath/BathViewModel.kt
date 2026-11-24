package com.qzxy.lite.ui.bath

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.core.ApiException
import com.qzxy.lite.core.ApiResponse
import com.qzxy.lite.core.DiagLog
import com.qzxy.lite.core.asYuanOrNull
import com.qzxy.lite.core.formatYuan
import com.qzxy.lite.core.isDeviceOfflineMessage
import com.qzxy.lite.data.bath.BathPhase
import com.qzxy.lite.data.bath.BathSessionStore
import com.qzxy.lite.data.bath.BathSnapshot
import com.qzxy.lite.data.repository.BathRepository
import com.qzxy.lite.ui.common.toUserMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject

data class BathUiState(
    val deviceLabel: String = "",
    val phase: BathPhase = BathPhase.IDLE,
    val orderNo: String? = null,
    val message: String? = null,
    val error: String? = null,
    val busy: Boolean = false,
    val isBleMode: Boolean = false,
    val bleStatus: String? = null,
)

/**
 * 洗澡控制：
 * 1. 在线 4G/TCP 设备：
 *    ① query/rateOrder/using 防重复（307=设备使用中）
 *    ② downRate/rateOrder 开阀下单
 *    ③ query/downRateResult 轮询开机结果
 *    ④ closeOrder → closeOrder/result/query → consumeOrder/result/query
 *
 * 2. 离线蓝牙水表（脱机水表）：
 *    高校未联网设备无法维持 TCP 长连接，调用 tcpDevice 会返回 306「设备不在线」；
 *    当遇到 306 或用户主动开启蓝牙直连时：
 *    ① 手机通过 BLE 连接水表 (GATT 0xFF00/0xFF01/0xFF02)
 *    ② 读取水表参数与随机数 (Cmd 0x23)
 *    ③ 向服务端申请开阀指令 (order/downRate/bluetooth/rateOrder)
 *    ④ 写入水表开阀 (Cmd 0x21) 并开始洗浴
 *    ⑤ 结束时 BLE 关阀 (Cmd 0x22)、采集水表账单 (Cmd 0x85)
 *    ⑥ 上传云端结算扣款 (order/upload/bluetooth/data) 并确认水表存储 (Cmd 0x86)
 */
class BathViewModel(
    private val bath: BathRepository,
    private val sessionStore: BathSessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(BathUiState())
    val state: StateFlow<BathUiState> = _state.asStateFlow()

    private var snCode: String? = null
    private var deviceSncode: String? = null

    fun bindDevice(label: String, sn: String?, deviceSncode: String? = null) {
        if (_state.value.deviceLabel.isNotEmpty()) return
        val target = sn?.takeIf { it.isNotBlank() }
        snCode = target
        this.deviceSncode = deviceSncode?.takeIf { it.isNotBlank() } ?: target
        DiagLog.d("bath", "bind label=$label sn=$target deviceSncode=${this.deviceSncode}")

        val snapshot = target?.let { sessionStore.get(it) }
        val restoredPhase = snapshot?.phase?.takeIf { it in ACTIVE_PHASES } ?: BathPhase.IDLE
        setState {
            it.copy(
                deviceLabel = label,
                phase = restoredPhase,
                orderNo = snapshot?.orderNo,
                message = snapshot?.message,
            )
        }
        if (restoredPhase == BathPhase.IDLE && snapshot?.phase == BathPhase.CLOSED) {
            sessionStore.remove(target!!)
        }

        if (target != null) {
            viewModelScope.launch {
                reconcile(target)
                while (isActive) {
                    delay(RECONCILE_INTERVAL_MS)
                    if (_state.value.phase == BathPhase.SHOWERING && !_state.value.isBleMode) {
                        reconcile(target)
                    }
                }
            }
        }
    }

    fun toggleBleMode() {
        if (_state.value.busy || _state.value.phase != BathPhase.IDLE) return
        setState {
            val newMode = !it.isBleMode
            it.copy(
                isBleMode = newMode,
                message = if (newMode) "已切换至蓝牙脱机模式（适用于未联网水表）" else "已切换至远程网络模式",
            )
        }
    }

    /** 与服务端对账（仅限远程 TCP 模式；脱机蓝牙设备不通过 TCP 轮询）。 */
    private suspend fun reconcile(sn: String) {
        if (_state.value.busy || _state.value.isBleMode) return
        val rsp = runCatching { bath.usingOrderRaw(sn, deviceSncode ?: sn) }.getOrNull() ?: return
        when {
            rsp.errorCode == 307 -> {
                val orderNo = rsp.orderNoOrNull() ?: return
                if (_state.value.phase != BathPhase.SHOWERING || _state.value.orderNo != orderNo) {
                    setState {
                        it.copy(
                            phase = BathPhase.SHOWERING,
                            orderNo = orderNo,
                            message = "设备正在用水中，已接管订单 $orderNo",
                        )
                    }
                }
            }

            rsp.errorCode == 0 -> {
                val snapshot = sessionStore.get(sn)
                val fresh = snapshot != null &&
                        System.currentTimeMillis() - snapshot.updatedAt < RECONCILE_GRACE_MS
                if (!fresh && _state.value.phase in ACTIVE_PHASES) {
                    setState {
                        it.copy(
                            phase = BathPhase.IDLE,
                            orderNo = null,
                            message = "设备当前没有进行中的订单，已回到待机",
                        )
                    }
                }
            }

            else -> Unit
        }
    }

    fun open() {
        val sn = snCode
        if (sn.isNullOrBlank()) {
            setState { it.copy(error = "设备 SN 缺失：请从扫描/列表进入并确认设备标识字段") }
            return
        }
        if (_state.value.busy) return
        if (_state.value.phase in BUSY_PHASES) return

        viewModelScope.launch {
            setState { it.copy(busy = true, error = null, message = null, phase = BathPhase.OPENING) }

            // 1. 若已经是 BLE 模式，直接走 BLE 开阀
            if (_state.value.isBleMode) {
                openViaBle(sn)
                return@launch
            }

            // 2. 否则先尝试远程 TCP 开阀
            try {
                val using = withTimeoutOrNull(PRE_CHECK_TIMEOUT_MS) {
                    runCatching { bath.usingOrderRaw(sn, deviceSncode ?: sn) }.getOrNull()
                }
                if (using?.errorCode == 307) {
                    val existing = using.orderNoOrNull()
                        ?: throw ApiException(ApiException.DATA_ERROR_CODE, "设备使用中但未返回 orderNo")
                    setState {
                        it.copy(
                            busy = false,
                            phase = BathPhase.SHOWERING,
                            orderNo = existing,
                            message = "设备已在用水中，接管订单 $existing",
                        )
                    }
                    return@launch
                }

                val openResp = bath.openRaw(sn, deviceSncode ?: sn)
                if (openResp.errorCode == 307) {
                    val existing = resolveByUsing(sn)
                        ?: throw ApiException(ApiException.DATA_ERROR_CODE, "设备使用中但未返回 orderNo")
                    setState {
                        it.copy(
                            busy = false,
                            phase = BathPhase.SHOWERING,
                            orderNo = existing,
                            message = "设备已在用水中，接管订单 $existing",
                        )
                    }
                    return@launch
                }
                if (openResp.errorCode != 0) {
                    throw ApiException(openResp.errorCode, openResp.effectiveMessage)
                }

                val hinted = openResp.data?.orderNo?.takeIf { it.isNotBlank() }
                setState {
                    it.copy(
                        busy = false,
                        phase = BathPhase.SHOWERING,
                        orderNo = hinted ?: it.orderNo,
                        message = if (hinted != null) "已开阀，供水开始" else "已开阀，供水开始（订单确认中…）",
                    )
                }

                if (hinted == null) {
                    val resolved = resolveOrderNo(sn, onlyWhileShowering = true)
                    if (_state.value.phase == BathPhase.SHOWERING) {
                        setState {
                            it.copy(
                                orderNo = resolved ?: it.orderNo,
                                message = if (resolved != null) "已开阀，供水进行中" else "已开阀，但未收到设备回执（请确认是否出水）",
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                DiagLog.d("bath", "open TCP failed: ${t.message}")

                // 核心适配：当检测到 306 设备离线时，自动切换到脱机蓝牙链路重试！
                if (t is ApiException && (t.code == ApiException.DEVICE_OFFLINE_CODE || isDeviceOfflineMessage(t.message))) {
                    DiagLog.d("bath", "Detected offline device (306), falling back to BLE bridge mode!")
                    setState {
                        it.copy(
                            isBleMode = true,
                            message = "检测到无网设备(306)，正在切换手机蓝牙直连开阀...",
                        )
                    }
                    openViaBle(sn)
                } else {
                    setState { it.copy(busy = false, phase = BathPhase.IDLE, error = t.toUserMessage()) }
                }
            }
        }
    }

    /** 蓝牙脱机开阀 */
    private suspend fun openViaBle(mac: String) {
        try {
            val rateData = bath.openBle(mac) { status ->
                setState { it.copy(bleStatus = status, message = status) }
            }
            val orderNo = rateData.orderNo
            val perMoney = rateData.perMoney
            setState {
                it.copy(
                    busy = false,
                    phase = BathPhase.SHOWERING,
                    orderNo = orderNo,
                    isBleMode = true,
                    message = "蓝牙开阀成功，供水进行中" + (if (!perMoney.isNullOrBlank()) "（预扣 $perMoney 元）" else ""),
                    error = null,
                )
            }
        } catch (bleErr: Throwable) {
            DiagLog.d("bath", "BLE open failed: ${bleErr.message}")
            setState {
                it.copy(
                    busy = false,
                    phase = BathPhase.IDLE,
                    error = "蓝牙开阀失败：${bleErr.toUserMessage()}。请确保已开启手机蓝牙并贴近水表",
                )
            }
        }
    }

    /** 关阀操作 */
    fun close() {
        val sn = snCode
        if (_state.value.busy) return
        viewModelScope.launch {
            setState { it.copy(busy = true, error = null, phase = BathPhase.CLOSING) }

            // 1. 若处于 BLE 模式，走 BLE 关阀 + 采集上传
            if (_state.value.isBleMode) {
                closeViaBle(sn)
                return@launch
            }

            // 2. 否则走 TCP 关阀
            try {
                if (sn.isNullOrBlank()) {
                    throw ApiException(ApiException.DATA_ERROR_CODE, "设备 SN 缺失，无法关阀")
                }
                val orderNo = _state.value.orderNo
                    ?: resolveByUsing(sn)
                    ?: resolveOrderNo(sn, onlyWhileShowering = false)
                    ?: throw ApiException(ApiException.DATA_ERROR_CODE, "未拿到订单号，无法关阀（请稍后重试）")

                bath.close(sn, orderNo)

                setState {
                    it.copy(
                        busy = false,
                        phase = BathPhase.CLOSED,
                        orderNo = orderNo,
                        message = "已关阀，正在结算…",
                    )
                }

                val confirmed = confirmClosed(sn, orderNo)
                val consumeYuan = pollSettledYuan(orderNo)
                val consumeText = consumeYuan?.let { "，本次消费 ¥${formatYuan(it)}" }
                    ?: "，账单结算中，稍后请在账单明细查看"
                setState {
                    it.copy(
                        phase = BathPhase.CLOSED,
                        message = (if (confirmed) "已关阀" else "关阀指令已下发（结果未确认）") + consumeText,
                    )
                }
                scheduleClosedReset()
            } catch (t: Throwable) {
                // 若 TCP 关阀返回离线，也尝试 BLE 关阀
                if (t is ApiException && (t.code == ApiException.DEVICE_OFFLINE_CODE || isDeviceOfflineMessage(t.message))) {
                    closeViaBle(sn)
                } else {
                    setState { it.copy(busy = false, phase = BathPhase.SHOWERING, error = t.toUserMessage()) }
                }
            }
        }
    }

    /** 蓝牙脱机关阀 */
    private suspend fun closeViaBle(mac: String?) {
        if (mac.isNullOrBlank()) {
            setState { it.copy(busy = false, error = "蓝牙 MAC 缺失，无法关阀") }
            return
        }
        val orderNo = _state.value.orderNo
        try {
            val uploadData = bath.closeBle(mac, orderNo) { status ->
                setState { it.copy(bleStatus = status, message = status) }
            }
            val consumeMoney = uploadData.consumeMoney
            val moneyText = if (!consumeMoney.isNullOrBlank()) "，本次消费 ¥$consumeMoney" else ""
            setState {
                it.copy(
                    busy = false,
                    phase = BathPhase.CLOSED,
                    orderNo = orderNo,
                    message = "蓝牙已关阀$moneyText",
                    error = null,
                )
            }
            scheduleClosedReset()
        } catch (bleErr: Throwable) {
            DiagLog.d("bath", "BLE close failed: ${bleErr.message}")
            setState {
                it.copy(
                    busy = false,
                    phase = BathPhase.SHOWERING,
                    error = "蓝牙关阀/结算失败：${bleErr.toUserMessage()}。请保持靠近水表重试",
                )
            }
        }
    }

    private fun scheduleClosedReset() {
        viewModelScope.launch {
            delay(CLOSED_RESET_MS)
            if (_state.value.phase == BathPhase.CLOSED) {
                setState { it.copy(phase = BathPhase.IDLE, orderNo = null, busy = false, isBleMode = false) }
            }
        }
    }

    private suspend fun resolveByUsing(sn: String): String? =
        runCatching { bath.usingOrderRaw(sn, deviceSncode ?: sn) }.getOrNull()?.orderNoOrNull()

    private fun ApiResponse<JsonObject>.orderNoOrNull(): String? =
        data?.get("orderNo")?.toString()?.trim('"')?.takeIf { it.isNotBlank() }

    private suspend fun resolveOrderNo(sn: String, onlyWhileShowering: Boolean): String? {
        for (i in 0 until POLL_TIMES) {
            if (onlyWhileShowering && _state.value.phase != BathPhase.SHOWERING) return null
            val poll = runCatching { bath.pollOpenRaw(sn, deviceSncode ?: sn) }.getOrNull()
            if (poll != null) {
                if (poll.errorCode != 0 && isDeviceOfflineMessage(poll.effectiveMessage)) {
                    throw ApiException(poll.errorCode, poll.effectiveMessage)
                }
                val no = poll.takeIf { it.errorCode == 0 }?.data?.orderNo
                if (!no.isNullOrBlank()) return no
            }
            if (i != POLL_TIMES - 1) delay(POLL_INTERVAL_MS)
        }
        return resolveByUsing(sn)
    }

    private suspend fun confirmClosed(sn: String, orderNo: String): Boolean {
        for (i in 0 until POLL_TIMES) {
            val rsp = runCatching { bath.closeResultRaw(sn, orderNo) }.getOrNull()
            if (rsp?.errorCode == 0) return true
            if (i != POLL_TIMES - 1) delay(POLL_INTERVAL_MS)
        }
        return false
    }

    private fun JsonObject.settledConsumeYuan(): Double? =
        listOf("consumeMoney", "consumeMoneyReal")
            .firstNotNullOfOrNull { key -> get(key).asYuanOrNull()?.takeIf { it > 0.0 } }

    private suspend fun pollSettledYuan(orderNo: String): Double? {
        for (i in 0 until SETTLE_POLL_TIMES) {
            val obj = runCatching { bath.consumeResult(orderNo) }.getOrNull()
            obj?.settledConsumeYuan()?.let { return it }
            if (i != SETTLE_POLL_TIMES - 1) delay(SETTLE_INTERVAL_MS)
        }
        return null
    }

    fun clearError() = setState { it.copy(error = null, message = null) }

    private fun setState(transform: (BathUiState) -> BathUiState) {
        val next = transform(_state.value)
        _state.value = next
        val sn = snCode ?: return
        if (next.phase == BathPhase.CLOSED) return
        sessionStore.put(
            sn,
            BathSnapshot(phase = next.phase, orderNo = next.orderNo, message = next.message),
        )
    }

    override fun onCleared() {
        super.onCleared()
        bath.disconnectBle()
    }

    private companion object {
        val BUSY_PHASES = setOf(BathPhase.OPENING, BathPhase.SHOWERING, BathPhase.CLOSING)
        val ACTIVE_PHASES = setOf(BathPhase.OPENING, BathPhase.SHOWERING, BathPhase.CLOSING)

        const val RECONCILE_INTERVAL_MS = 5_000L
        const val RECONCILE_GRACE_MS = 10_000L
        const val CLOSED_RESET_MS = 6_000L
        const val PRE_CHECK_TIMEOUT_MS = 2_000L

        const val POLL_TIMES = 15
        const val POLL_INTERVAL_MS = 1_000L

        const val SETTLE_POLL_TIMES = 6
        const val SETTLE_INTERVAL_MS = 1_500L
    }
}
