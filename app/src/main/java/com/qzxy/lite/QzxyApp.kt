package com.qzxy.lite

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.qzxy.lite.update.UpdateManager

class QzxyApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // 诊断版：先起日志（崩溃处理器要尽早装），再建容器（网络拦截器依赖它）。
        if (BuildConfig.DIAG_LOG) com.qzxy.lite.core.DiagLog.init(this)
        container = AppContainer(this)
        registerApkCleanup()
    }

    /**
     * 覆盖安装完成后自动删除下载目录里的更新包。
     * PACKAGE_REPLACED 只在"本包被替换"时触发；NOT_EXPORTED 足够（广播来自系统）。
     */
    private fun registerApkCleanup() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply {
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val replaced = intent.data?.schemeSpecificPart
                if (replaced == packageName) {
                    UpdateManager.clearPending(context)
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as QzxyApp).container
