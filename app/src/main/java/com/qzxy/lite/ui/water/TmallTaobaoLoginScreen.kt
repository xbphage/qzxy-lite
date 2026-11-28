package com.qzxy.lite.ui.water

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Message
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qzxy.lite.tmall.CookieRow
import com.qzxy.lite.tmall.TmallAliLogin
import com.qzxy.lite.tmall.TmallMtopClient
import com.qzxy.lite.tmall.mergeTmallCookieRows
import com.qzxy.lite.ui.common.ErrorNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

/**
 * 淘宝账号 H5 网页登录页：
 * 内嵌官方淘宝登录页，登录完成后按 `.confong.cn` 优先合并规则提取会话 Cookie 并核验校园用户。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TmallTaobaoLoginScreen(
    onResult: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("淘宝登录") }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = TmallMtopClient.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                    error = null
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    title = view?.title?.takeIf { it.isNotBlank() } ?: "淘宝登录"
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    val u = request?.url?.toString() ?: return true
                    return !(u.startsWith("http://") || u.startsWith("https://"))
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean = false
            }
        }
    }

    DisposableEffect(Unit) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        webView.loadUrl(START_URL)
        onDispose {
            runCatching { cm.flush() }
            runCatching {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    fun finishLogin() {
        if (busy) return
        val jar = harvestAllCookies()
        val account = jar["unb"] ?: jar["munb"]
        if (account.isNullOrBlank()) {
            error = "未检测到登录账号标识，请先在上方页面完成登录"
            return
        }
        busy = true
        error = null
        scope.launch {
            val campusErr = withContext(Dispatchers.IO) {
                TmallAliLogin(TmallMtopClient(LinkedHashMap(jar))).confirmCampusUser()
            }
            busy = false
            if (campusErr == null) {
                onResult(jar)
            } else {
                error = "淘宝登录成功，但校园侧校验未通过：$campusErr"
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onCancel) { Text("取消") }
            Column(Modifier.weight(1f)) {
                Text("淘宝账号登录", style = MaterialTheme.typography.titleMedium)
                Text(
                    title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = { finishLogin() }, enabled = !busy) {
                Text(if (busy) "校验中…" else "我已完成")
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            Spacer(Modifier.height(4.dp))
        }

        error?.let {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ErrorNotice(it)
                TextButton(onClick = { webView.loadUrl(CAMPUS_ACTIVATE_URL) }) {
                    Text("访问校园 H5 页同步校园 Cookie ›")
                }
            }
        }

        AndroidView(
            factory = { webView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Text(
            "密码仅输入至官方淘宝登录页，本应用不触碰密码；成功后仅加密保存必要会话 Cookie。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

private fun harvestAllCookies(): Map<String, String> {
    val cm = CookieManager.getInstance()
    runCatching { cm.flush() }
    val rows = mutableListOf<CookieRow>()
    for (u in HARVEST_HOSTS) {
        val host = runCatching { URI(u).host.orEmpty() }.getOrDefault("")
        val raw = runCatching { cm.getCookie(u) }.getOrNull() ?: continue
        for (part in raw.split(';')) {
            val k = part.substringBefore('=', "").trim()
            val v = part.substringAfter('=', "").trim()
            if (k.isNotEmpty() && v.isNotEmpty()) {
                rows += CookieRow(host = host, name = k, value = v)
            }
        }
    }
    return mergeTmallCookieRows(rows)
}

private val HARVEST_HOSTS = listOf(
    "https://www.taobao.com",
    "https://h5api.m.taobao.com",
    "https://m.taobao.com",
    "https://login.taobao.com",
    "https://share.confong.cn",
    "https://biz.confong.cn",
)

private const val START_URL =
    "https://login.taobao.com/member/login.jhtml?redirectURL=https%3A%2F%2Fwww.taobao.com%2F"

private const val CAMPUS_ACTIVATE_URL =
    "https://biz.confong.cn/app/tmall-xiaoyuan/campus-dormitory-m/home?hideNavigator=true&loadType=normal&loginRequired=true"
