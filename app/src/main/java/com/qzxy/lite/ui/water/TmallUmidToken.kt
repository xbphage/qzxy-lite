package com.qzxy.lite.ui.water

import android.annotation.SuppressLint
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qzxy.lite.BuildConfig
import com.qzxy.lite.tmall.TmallMtopClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 在后台 1dp WebView 加载淘宝移动登录页，从 `performance` 资源列表中提取 `bx-umidtoken`，
 * 无需加载支付宝/天猫原生安全库即可通过首层参数识别。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun rememberTmallUmidToken(): String {
    val context = LocalContext.current
    var token by remember { mutableStateOf("") }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = TmallMtopClient.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = blockExternalScheme(request?.url?.toString())

                @Deprecated("兼容旧 API")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                    blockExternalScheme(url)
            }
            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?,
                ): Boolean = false
            }
            loadUrl(UMID_PAGE)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    LaunchedEffect(Unit) {
        repeat(40) {
            if (token.isNotBlank()) return@LaunchedEffect
            delay(1_000)
            val js = "performance.getEntriesByType('resource').map(function(r){return r.name})" +
                ".filter(function(n){return n.indexOf('bx-umidtoken=')>=0}).join('|')"
            val raw = webView.evaluateJavascriptSuspend(js)
            val found = RE_UMID.find(raw)?.groupValues?.get(1)
            if (!found.isNullOrBlank()) {
                token = found
                if (BuildConfig.DEBUG) Log.d("TmallWater", "umid token ready len=${found.length}")
            }
        }
    }

    Box(Modifier.size(1.dp)) {
        AndroidView(factory = { webView })
    }
    return token
}

private suspend fun WebView.evaluateJavascriptSuspend(script: String): String =
    suspendCancellableCoroutine { cont ->
        try {
            evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value ?: "")
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            if (cont.isActive) cont.resume("")
        }
    }

private fun blockExternalScheme(url: String?): Boolean {
    if (url == null) return true
    val low = url.lowercase()
    return !(low.startsWith("http://") || low.startsWith("https://"))
}

private val RE_UMID = Regex("""bx-umidtoken=([^&|]+)""")
private const val UMID_PAGE = "https://login.m.taobao.com/login.htm"
