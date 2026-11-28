package com.qzxy.lite.ui.wallet

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.data.repository.PayChannel
import com.qzxy.lite.data.repository.RechargeTier
import com.qzxy.lite.data.repository.WalletRepository
import com.qzxy.lite.data.session.SessionStore
import com.qzxy.lite.tmall.TmallMtopError
import com.qzxy.lite.tmall.TmallWalletAccount
import com.qzxy.lite.tmall.TmallWalletRecord
import com.qzxy.lite.tmall.TmallWaterRepository
import com.qzxy.lite.ui.common.toUserMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class WalletUiState(
    val balance: String = "--",
    val summaryHint: String? = null,
    /** 充值档位（服务端 settlement/recharge/package/list 实测：5/10/20/30/50 元）。 */
    val tiers: List<RechargeTier> = emptyList(),
    val amount: String = "",
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val recharging: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val tmallLoggedIn: Boolean = false,
    val tmallLoading: Boolean = false,
    val tmallRefreshing: Boolean = false,
    val tmallAccounts: List<TmallWalletAccount> = emptyList(),
    val tmallSelectedIndex: Int = 0,
    val tmallRecords: List<TmallWalletRecord> = emptyList(),
    val tmallActivityName: String = "",
    val tmallError: String? = null,
)

/** 钱包：趣智余额与官方支付宝直充 + 天猫校园水卡余额/冻结/近期流水（只读）。 */
class WalletViewModel(
    private val wallet: WalletRepository,
    private val sessionStore: SessionStore,
    private val tmallRepo: TmallWaterRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    private var refreshJob: Job? = null
    private var tmallRefreshJob: Job? = null
    private var lastRefreshAtMs: Long = 0L

    init {
        viewModelScope.launch {
            sessionStore.session.collect { cur ->
                if (cur?.isLoggedIn == true) {
                    refreshQzxy(initial = true, force = true)
                } else {
                    refreshJob?.cancel()
                    lastRefreshAtMs = 0L
                    _state.update {
                        it.copy(
                            balance = "--",
                            summaryHint = null,
                            tiers = emptyList(),
                            amount = "",
                            loading = false,
                            refreshing = false,
                            recharging = false,
                            error = null,
                            message = null,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            tmallRepo.sessionStore.cookies.collect { cookies ->
                val loggedIn = cookies.isNotEmpty()
                if (loggedIn) {
                    _state.update { it.copy(tmallLoggedIn = true) }
                    refreshTmall(initial = _state.value.tmallAccounts.isEmpty(), includeRecords = true)
                } else {
                    tmallRefreshJob?.cancel()
                    _state.update {
                        it.copy(
                            tmallLoggedIn = false,
                            tmallLoading = false,
                            tmallRefreshing = false,
                            tmallAccounts = emptyList(),
                            tmallSelectedIndex = 0,
                            tmallRecords = emptyList(),
                            tmallActivityName = "",
                            tmallError = null,
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            tmallRepo.walletOverview.collect { overview ->
                if (overview == null || !tmallRepo.sessionStore.isLoggedIn) return@collect
                _state.update { st ->
                    val idx = st.tmallSelectedIndex.coerceIn(0, (overview.accounts.size - 1).coerceAtLeast(0))
                    st.copy(
                        tmallAccounts = overview.accounts,
                        tmallSelectedIndex = idx,
                        tmallRecords = if (overview.records.isNotEmpty()) overview.records else st.tmallRecords,
                        tmallActivityName = overview.freeActivityName,
                    )
                }
            }
        }
        viewModelScope.launch {
            wallet.latestBalance.filterNotNull().collect { latest ->
                if (sessionStore.current()?.isLoggedIn == true) {
                    _state.update { it.copy(balance = latest) }
                }
            }
        }
    }

    fun refresh(initial: Boolean = false, force: Boolean = true) {
        if (sessionStore.current()?.isLoggedIn == true) {
            refreshQzxy(initial = initial, force = force)
        }
        if (tmallRepo.sessionStore.isLoggedIn) {
            refreshTmall(initial = initial && _state.value.tmallAccounts.isEmpty(), includeRecords = true, force = force)
        }
    }

    private fun refreshQzxy(initial: Boolean = false, force: Boolean = true) {
        if (sessionStore.current()?.isLoggedIn != true) return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshAtMs < SILENT_REFRESH_COOLDOWN_MS) return
        if (_state.value.loading || _state.value.refreshing) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _state.update {
                if (initial) it.copy(loading = true, error = null)
                else it.copy(refreshing = true, error = null)
            }
            runCatching {
                val info = wallet.wallet()
                val tiers = if (_state.value.tiers.isEmpty() || force) {
                    runCatching { wallet.rechargeTiers() }.getOrDefault(_state.value.tiers)
                } else {
                    _state.value.tiers
                }
                Pair(info, tiers)
            }
                .onSuccess { (info, tiers) ->
                    lastRefreshAtMs = System.currentTimeMillis()
                    _state.update {
                        val fallbackAmount = if (it.amount.isBlank()) tiers.firstOrNull()?.amount ?: "" else it.amount
                        it.copy(
                            loading = false,
                            refreshing = false,
                            balance = info.display,
                            tiers = tiers,
                            amount = fallbackAmount,
                        )
                    }
                }
                .onFailure { t ->
                    _state.update { it.copy(loading = false, refreshing = false, error = t.toUserMessage()) }
                }
        }
    }

    fun refreshTmall(initial: Boolean = false, includeRecords: Boolean = true, force: Boolean = true) {
        if (!tmallRepo.sessionStore.isLoggedIn) return
        if (!force && (_state.value.tmallLoading || _state.value.tmallRefreshing)) return
        tmallRefreshJob?.cancel()
        tmallRefreshJob = viewModelScope.launch {
            _state.update {
                if (initial) it.copy(tmallLoading = true, tmallError = null)
                else it.copy(tmallRefreshing = true, tmallError = null)
            }
            try {
                val selectedIdx = _state.value.tmallSelectedIndex
                val overview = withContext(Dispatchers.IO) {
                    tmallRepo.loadWalletOverview(includeRecords = includeRecords, preferredIndex = selectedIdx)
                }
                _state.update { st ->
                    val safeIdx = selectedIdx.coerceIn(0, (overview.accounts.size - 1).coerceAtLeast(0))
                    st.copy(
                        tmallLoading = false,
                        tmallRefreshing = false,
                        tmallAccounts = overview.accounts,
                        tmallSelectedIndex = safeIdx,
                        tmallRecords = overview.records,
                        tmallActivityName = overview.freeActivityName,
                        tmallError = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (t is TmallMtopError && t.code == "FAIL_SYS_SESSION_EXPIRED") {
                    tmallRepo.logout()
                }
                val msg = if (t is TmallMtopError) "${t.human()}（${t.code}）" else (t.message ?: t.javaClass.simpleName)
                _state.update { it.copy(tmallLoading = false, tmallRefreshing = false, tmallError = msg) }
            }
        }
    }

    fun selectTmallAccount(index: Int) {
        val accounts = _state.value.tmallAccounts
        if (index !in accounts.indices || index == _state.value.tmallSelectedIndex) return
        _state.update { it.copy(tmallSelectedIndex = index) }
        refreshTmall(initial = false, includeRecords = true, force = true)
    }

    fun onAmountChange(v: String) =
        _state.update { it.copy(amount = v.filter { c -> c.isDigit() || c == '.' }) }

    /** 充值：下单拿 SDK 拉起串 → 拉起支付宝 SDK（9000=成功）→ 刷新余额。 */
    fun recharge(channel: PayChannel, context: Context) {
        val amount = _state.value.amount.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update { it.copy(error = "请输入有效充值金额") }
            return
        }
        // 实测服务端对充值金额有下限（5/10 元视渠道与活动而定）；客户端先按 5 元拦截，最终以服务端返回为准。
        if (amount < MIN_RECHARGE_YUAN) {
            _state.update { it.copy(error = "最低充值 ${MIN_RECHARGE_YUAN.toInt()} 元") }
            return
        }
        if (_state.value.recharging) return
        val activity = context as? Activity
        if (activity == null) {
            _state.update { it.copy(error = "Activity 不可用") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(recharging = true, error = null, message = null) }
            try {
                val orderString = when (channel) {
                    PayChannel.WECHAT -> wallet.createWeChatRecharge(_state.value.amount)
                    PayChannel.ALIPAY -> wallet.createAliPayRecharge(_state.value.amount)
                }
                // 拉起官方 SDK（支付宝已接入；微信 SDK 未接入）
                if (channel == PayChannel.ALIPAY) {
                    val result = wallet.payWithAlipaySdk(activity, orderString)
                    val status = result["resultStatus"]
                    if (status == "9000") {
                        refresh()
                        _state.update { it.copy(recharging = false, message = "充值成功") }
                        return@launch
                    }
                    _state.update {
                        it.copy(
                            recharging = false,
                            message = "支付未完成：resultStatus=$status ${result["memo"]?.take(80).orEmpty()}",
                        )
                    }
                } else {
                    _state.update { it.copy(recharging = false, message = "微信支付 SDK 未接入（当前保留支付宝）") }
                }
            } catch (t: Throwable) {
                _state.update { it.copy(recharging = false, error = t.toUserMessage()) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null, message = null) }

    companion object {
        /** 实测充值金额下限（元）；不同渠道/活动可能为 10，最终以服务端校验为准。 */
        const val MIN_RECHARGE_YUAN = 5.0
        private const val SILENT_REFRESH_COOLDOWN_MS = 5_000L
    }
}
