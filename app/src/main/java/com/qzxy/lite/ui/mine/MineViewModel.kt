package com.qzxy.lite.ui.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qzxy.lite.data.repository.BillRepository
import com.qzxy.lite.data.repository.CampusRepository
import com.qzxy.lite.data.session.SessionStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class MineUiState(
    val school: String = "校园信息加载中…",
    val projectId: String = "",
    val maskedPhone: String = "",
    val refundRaw: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/** 我的页面：学校（打码手机号）+ 账单明细入口 + 退款进度 + 退出登录。 */
class MineViewModel(
    private val campus: CampusRepository,
    private val bill: BillRepository,
    private val sessionStore: SessionStore,
) : ViewModel() {

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            sessionStore.session.collect { cur ->
                if (cur?.isLoggedIn == true) {
                    load()
                } else {
                    _state.update {
                        it.copy(
                            school = "未登录",
                            projectId = "",
                            maskedPhone = "",
                            refundRaw = null,
                            loading = false,
                            error = null,
                        )
                    }
                }
            }
        }
    }

    fun load() {
        val cur = sessionStore.current()
        if (cur?.isLoggedIn != true) {
            _state.update {
                it.copy(
                    school = "未登录",
                    projectId = "",
                    maskedPhone = "",
                    refundRaw = null,
                    loading = false,
                    error = null,
                )
            }
            return
        }
        val phone = cur.telephone
        _state.update {
            it.copy(
                maskedPhone = maskPhone(phone),
                projectId = cur.projectId,
            )
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { campus.projectInfo() }
                .onSuccess { json ->
                    val name = findName(json)
                    _state.update {
                        it.copy(school = name ?: if (it.projectId.isNotBlank()) "校园 ${it.projectId}" else "未绑定校园")
                    }
                }
                .onFailure {
                    _state.update {
                        if (it.school == "校园信息加载中…" || it.school == "未登录") {
                            it.copy(school = if (it.projectId.isNotBlank()) "校园 ${it.projectId}" else "未绑定校园")
                        } else it
                    }
                }
            runCatching { bill.refundList() }
                .onSuccess { el -> _state.update { it.copy(refundRaw = el.toString().take(900)) } }
                .onFailure { _state.update { it.copy(refundRaw = null) } }
            _state.update { it.copy(loading = false) }
        }
    }

    /** 手机号打码：前 3 + **** + 后 4。 */
    fun maskPhone(p: String): String =
        if (p.length >= 7) p.take(3) + "****" + p.takeLast(4) else p

    /** 在响应 JSON 里递归找第一个像"名称"的字段。 */
    private fun findName(element: JsonElement, depth: Int = 0): String? {
        if (depth > 6) return null
        when (element) {
            is JsonObject -> {
                for (k in listOf("projectName", "schoolName", "name", "prjName")) {
                    val v = element[k]
                    if (v is JsonPrimitive && v.content.isNotBlank()) return v.content
                }
                for (v in element.values) {
                    findName(v, depth + 1)?.let { return it }
                }
            }
            is kotlinx.serialization.json.JsonArray -> element.forEach { findName(it, depth + 1)?.let { return it } }
            else -> {}
        }
        return null
    }
}
