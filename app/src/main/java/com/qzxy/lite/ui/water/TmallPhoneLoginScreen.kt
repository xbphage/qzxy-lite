package com.qzxy.lite.ui.water

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qzxy.lite.core.DiagLog
import com.qzxy.lite.tmall.TmallAliLogin
import com.qzxy.lite.tmall.TmallMtopClient
import com.qzxy.lite.ui.common.ErrorNotice
import com.qzxy.lite.ui.common.SoftNotice
import com.qzxy.lite.ui.common.verticalScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 天猫校园手机号 + 短信验证码 + 阿里滑块人机验证登录页。
 */
@Composable
fun TmallPhoneLoginScreen(
    onResult: (Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val client = remember { TmallMtopClient() }
    val ali = remember {
        val dm = context.resources.displayMetrics
        TmallAliLogin(client, "${dm.widthPixels}x${dm.heightPixels}")
    }
    val umid = rememberTmallUmidToken()

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeSent by remember { mutableStateOf(false) }
    var captchaUrl by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }
    var cooldown by remember { mutableIntStateOf(0) }

    fun sendSms() {
        if (busy) return
        if (phone.length != 11) {
            error = "请输入 11 位手机号"
            return
        }
        busy = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { ali.sendSms(phone, umid) }
            DiagLog.d(
                "tmall_sms",
                "umidLen=${umid.length} -> " + when (r) {
                    is TmallAliLogin.SendResult.Sent -> "SENT"
                    is TmallAliLogin.SendResult.NeedCaptcha -> "NEED_CAPTCHA"
                    is TmallAliLogin.SendResult.Failed -> "${r.code}: ${r.message}"
                },
            )
            busy = false
            when (r) {
                is TmallAliLogin.SendResult.Sent -> {
                    codeSent = true
                    hint = "验证码已发送，请查收短信"
                }
                is TmallAliLogin.SendResult.NeedCaptcha -> {
                    hint = "需要完成滑块人机验证，滑动后将自动续发验证码"
                    captchaUrl = r.url
                }
                is TmallAliLogin.SendResult.Failed -> {
                    error = "${r.code}：${r.message}"
                    if (r.code == "RGV587_ERROR" && cooldown == 0) {
                        cooldown = 120
                        while (cooldown > 0) {
                            delay(1_000)
                            cooldown -= 1
                        }
                    }
                }
            }
        }
    }

    fun doLogin() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val res = withContext(Dispatchers.IO) { ali.login(phone, code, umid) }
            res.onSuccess { jar ->
                val campusErr = withContext(Dispatchers.IO) { ali.confirmCampusUser() }
                busy = false
                if (campusErr != null) {
                    error = "登录成功，但校园侧校验未通过：$campusErr"
                } else {
                    onResult(jar)
                }
            }.onFailure { e ->
                busy = false
                error = e.message ?: "登录失败"
            }
        }
    }

    captchaUrl?.let { url ->
        BackHandler { captchaUrl = null }
        TmallCaptchaScreen(
            url = url,
            seed = client.export(),
            onCancel = { captchaUrl = null },
            onSolved = {
                client.merge(harvestCaptchaCookies())
                captchaUrl = null
                sendSms()
            },
        )
        return
    }

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
            }
            Column {
                Text("天猫校园验证码登录", style = MaterialTheme.typography.titleLarge)
                Text(
                    "阿里 UCC 校园手机号体系（带滑块人机验证）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScrollbar(scroll)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter(Char::isDigit).take(11) },
                        label = { Text("手机号") },
                        leadingIcon = { Icon(Icons.Rounded.Smartphone, contentDescription = null) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (codeSent) {
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it.filter(Char::isDigit).take(6) },
                            label = { Text("短信验证码") },
                            leadingIcon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
                            singleLine = true,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Button(
                        onClick = { if (codeSent) doLogin() else sendSms() },
                        enabled = !busy && cooldown == 0 && phone.length == 11 &&
                            (!codeSent || code.length >= 4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp),
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            when {
                                busy -> "处理中…"
                                cooldown > 0 -> "${cooldown}s 后可重试"
                                codeSent -> "登录"
                                else -> "发送验证码"
                            },
                        )
                    }

                    if (codeSent) {
                        OutlinedButton(
                            onClick = { sendSms() },
                            enabled = !busy && cooldown == 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("重新发送验证码") }
                    }
                }
            }

            Text(
                if (umid.isBlank()) "正在计算阿里设备指纹（约 5~15 秒，生成后成功率更高）…"
                else "设备指纹已就绪",
                style = MaterialTheme.typography.bodySmall,
                color = if (umid.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )

            hint?.let { SoftNotice(it) }
            error?.let { ErrorNotice(it) }

            Text(
                "注：若阿里风控频繁拦截短信，可返回改用「淘宝账号网页登录」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TmallCaptchaScreen(
    url: String,
    seed: Map<String, String>,
    onCancel: () -> Unit,
    onSolved: () -> Unit,
) {
    val context = LocalContext.current
    var done by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = TmallMtopClient.USER_AGENT
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean = request?.url?.toString()?.startsWith("http") == false
            }
        }
    }

    DisposableEffect(Unit) {
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(webView, true)
        listOf("x5sec", "_x5sec").forEach { name ->
            runCatching { cm.setCookie(CAPTCHA_HOST, "$name=; Max-Age=0; path=/") }
            runCatching { cm.setCookie("https://www.taobao.com", "$name=; Max-Age=0; path=/") }
        }
        seed.forEach { (k, v) ->
            runCatching { cm.setCookie(CAPTCHA_HOST, "$k=$v; path=/") }
        }
        runCatching { cm.flush() }
        webView.loadUrl(url)
        onDispose {
            runCatching { cm.flush() }
            runCatching {
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.removeAllViews()
                webView.destroy()
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
            Text(
                "完成滑块后点右侧按钮",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (!done) {
                        done = true
                        onSolved()
                    }
                },
            ) { Text("验证已完成") }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { webView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private const val CAPTCHA_HOST = "https://h5api.m.taobao.com"

private fun harvestCaptchaCookies(): Map<String, String> {
    val cm = CookieManager.getInstance()
    runCatching { cm.flush() }
    val jar = linkedMapOf<String, String>()
    for (u in listOf(CAPTCHA_HOST, "https://www.taobao.com")) {
        val raw = runCatching { cm.getCookie(u) }.getOrNull() ?: continue
        for (part in raw.split(';')) {
            val k = part.substringBefore('=', "").trim()
            if (k.isEmpty()) continue
            jar[k] = part.substringAfter('=', "").trim()
        }
    }
    return jar
}
