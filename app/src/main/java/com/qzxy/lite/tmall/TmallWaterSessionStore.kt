package com.qzxy.lite.tmall

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class CookieRow(val host: String, val name: String, val value: String)

/**
 * 多域 Cookie 行合并（保留 `.confong.cn` 的 `cookie2` 覆盖 `.taobao.com` 同名值）。
 */
internal fun mergeTmallCookieRows(
    rows: List<CookieRow>,
    confongDomain: String = TmallMtopClient.CONFONG_DOMAIN,
): Map<String, String> {
    val jar = LinkedHashMap<String, String>()
    var confongCookie2: String? = null
    for (row in rows) {
        if (row.value.isEmpty()) continue
        val cleanHost = row.host.removePrefix(".").lowercase()
        if (row.name == "cookie2" && (cleanHost == confongDomain || cleanHost.endsWith(".$confongDomain"))) {
            confongCookie2 = row.value
        }
        jar[row.name] = row.value
    }
    confongCookie2?.let { jar["cookie2"] = it }
    return jar
}

/**
 * 天猫校园饮水会话加密存储（Android Keystore + EncryptedSharedPreferences AES256-GCM/SIV）。
 */
class TmallWaterSessionStore(context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences = openOrRebuildEncryptedPrefs(context)

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private fun openOrRebuildEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            createEncryptedPrefs(context)
        } catch (e: Exception) {
            runCatching { context.deleteSharedPreferences(PREF_NAME) }
            try {
                createEncryptedPrefs(context)
            } catch (e2: Exception) {
                throw IllegalStateException("天猫校园加密会话初始化及重建均失败：${e2.message}", e2)
            }
        }
    }

    private val _cookies = MutableStateFlow(load())
    val cookies: StateFlow<Map<String, String>> = _cookies.asStateFlow()

    fun current(): Map<String, String> = _cookies.value

    val isLoggedIn: Boolean get() = _cookies.value.isNotEmpty()

    val accountId: String get() = extractAccountId(_cookies.value)

    val maskedAccount: String get() = maskAccount(accountId)

    @Synchronized
    fun adopt(jar: Map<String, String>) {
        val filtered = forStorage(jar)
        if (filtered.isEmpty()) return
        prefs.edit().putString(KEY_COOKIES, json.encodeToString(filtered)).apply()
        _cookies.value = filtered
    }

    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        _cookies.value = emptyMap()
    }

    private fun load(): Map<String, String> {
        val raw = prefs.getString(KEY_COOKIES, null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(raw)
        }.getOrDefault(emptyMap())
    }

    companion object {
        private const val PREF_NAME = "qzxy_tmall_water_auth"
        private const val KEY_COOKIES = "cookies_v1"

        private val DENY = setOf("tfstk", "_samesite_flag_", "cancelledSubSites", "APLUS_LS_KEY")

        internal fun forStorage(jar: Map<String, String>): Map<String, String> =
            jar.filterKeys { it !in DENY && it.isNotBlank() }

        fun extractAccountId(jar: Map<String, String>): String =
            jar["munb"]?.takeIf { it.isNotBlank() }
                ?: jar["unb"]?.takeIf { it.isNotBlank() }
                ?: ""

        fun maskAccount(id: String): String = when {
            id.isBlank() -> ""
            id.length <= 6 -> id
            else -> id.take(5) + "***" + id.takeLast(3)
        }
    }
}
