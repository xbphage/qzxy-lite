package com.qzxy.lite

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.qzxy.lite.data.session.Session
import com.qzxy.lite.ui.bath.BathScreen
import com.qzxy.lite.ui.bill.BillDetailScreen
import com.qzxy.lite.ui.campus.CampusScreen
import com.qzxy.lite.ui.common.DisclaimerDialog
import com.qzxy.lite.ui.mine.MineScreen
import com.qzxy.lite.ui.login.LoginScreen
import com.qzxy.lite.ui.scan.ScanScreen
import com.qzxy.lite.ui.theme.QzxyTheme
import com.qzxy.lite.ui.wallet.WalletScreen
import com.qzxy.lite.update.ReadyUpdate
import com.qzxy.lite.update.UpdateManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            QzxyTheme { QzxyNavHost() }
        }
    }
}

@Composable
private fun QzxyNavHost() {
    val container = LocalContext.current.appContainer
    val session: Session? by container.sessionStore.session.collectAsState()
    val disclaimerAccepted by container.disclaimerStore.accepted.collectAsState()
    val navController = rememberNavController()
    // 首次组合时决定起点：已有会话直接进设备列表，否则进登录页（登录后 id 自动获取）。
    val startDestination = remember { if (session?.isLoggedIn == true) Routes.CAMPUS else Routes.LOGIN }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route

    /**
     * 统一返回：栈里有上一页就回退，否则回首页。
     * 直接 popBackStack() 在栈只剩一页（如从通知/恢复态直接进入 bath）时会返回 false，
     * 表现为按钮点了没反应，用户再按系统返回键就直接退出到桌面。
     */
    fun goBack() {
        if (!navController.popBackStack()) {
            navController.navigate(Routes.CAMPUS) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    val scope = rememberCoroutineScope()

    // 会话失效（logout/clear）时回到登录页；已有会话时在后台检查并自动续期（对标原版 MainActivity.W1）。
    LaunchedEffect(session?.isLoggedIn) {
        if (session?.isLoggedIn != true &&
            navController.currentDestination?.route != Routes.LOGIN
        ) {
            navController.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
        } else if (session?.isLoggedIn == true) {
            container.authRepository.checkAndRenewSession()
        }
    }

    // —— 应用内静默更新：有已下好的包可立即复用（不重复拉取同版本），同时仍会向远端检查是否有更新版本（v1.0.6+）。 ——
    val appContext = LocalContext.current
    val autoUpdateEnabled by UpdateManager.autoUpdateEnabledFlow(appContext).collectAsState()
    var readyUpdate by remember { mutableStateOf<ReadyUpdate?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var dismissedVersion by remember { mutableStateOf<String?>(null) }
    var lastAutoCheckAtMs by remember { mutableStateOf(0L) }
    var hasCompletedInitialAutoCheck by remember { mutableStateOf(false) }
    val currentSession by rememberUpdatedState(session)
    val currentDisclaimerAccepted by rememberUpdatedState(disclaimerAccepted)
    val currentAutoUpdateEnabled by rememberUpdatedState(autoUpdateEnabled)

    fun checkUpdate(notifyWhenLatest: Boolean) {
        if (isCheckingUpdate) {
            if (notifyWhenLatest) {
                Toast.makeText(appContext, "正在检查/准备更新，请稍候…", Toast.LENGTH_SHORT).show()
            }
            return
        }
        if (notifyWhenLatest) {
            dismissedVersion = null
        }
        isCheckingUpdate = true
        scope.launch {
            try {
                val cachedReady = UpdateManager.getVerifiedReadyUpdate(appContext, BuildConfig.VERSION_NAME)
                if (!notifyWhenLatest && cachedReady != null && dismissedVersion != cachedReady.version) {
                    // 先复用本地已校验完成的安装包提示，同时继续向远端核对是否有比 cachedReady 更新的版本
                    readyUpdate = cachedReady
                }

                val remote = UpdateManager.fetchLatest()
                if (remote != null && !notifyWhenLatest) {
                    lastAutoCheckAtMs = System.currentTimeMillis()
                }

                if (remote != null && UpdateManager.isNewer(remote.version, BuildConfig.VERSION_NAME)) {
                    if (cachedReady != null && !UpdateManager.isNewer(remote.version, cachedReady.version)) {
                        if (notifyWhenLatest || dismissedVersion != cachedReady.version) {
                            readyUpdate = cachedReady
                        }
                        return@launch
                    }
                    if (!notifyWhenLatest && dismissedVersion == remote.version) {
                        return@launch
                    }
                    if (notifyWhenLatest) {
                        Toast.makeText(
                            appContext,
                            "发现新版本 ${remote.version}，正在静默下载并校验签名…",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    val prepared = runCatching {
                        UpdateManager.prepareUpdateSilently(appContext, remote)
                    }
                    prepared.onSuccess {
                        if (notifyWhenLatest || currentAutoUpdateEnabled) {
                            readyUpdate = it
                        }
                    }.onFailure { err ->
                        if (notifyWhenLatest) {
                            Toast.makeText(
                                appContext,
                                "更新下载或签名校验失败：${err.message ?: "请稍后再试"}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                } else if (cachedReady != null) {
                    if (notifyWhenLatest || dismissedVersion != cachedReady.version) {
                        readyUpdate = cachedReady
                    }
                } else if (remote != null && notifyWhenLatest) {
                    Toast.makeText(appContext, "当前已是最新版本 ${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                } else if (remote == null && notifyWhenLatest) {
                    Toast.makeText(appContext, "检查更新失败，请稍后再试", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isCheckingUpdate = false
            }
        }
    }

    val currentCheckUpdate by rememberUpdatedState<(Boolean) -> Unit> { checkUpdate(it) }

    // 从后台切回前台时：1) 主动续期登录会话；2) 距上次远端检查超过间隔时自动重检更新，避免发现旧版本后不再检出更新版本
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (currentSession?.isLoggedIn == true) {
                    scope.launch { container.authRepository.checkAndRenewSession() }
                }
                val now = System.currentTimeMillis()
                if (hasCompletedInitialAutoCheck &&
                    currentDisclaimerAccepted &&
                    currentAutoUpdateEnabled &&
                    now - lastAutoCheckAtMs >= AUTO_CHECK_INTERVAL_MS
                ) {
                    currentCheckUpdate(false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(disclaimerAccepted, autoUpdateEnabled) {
        if (disclaimerAccepted && autoUpdateEnabled) {
            if (!hasCompletedInitialAutoCheck) {
                delay(1_500)
                hasCompletedInitialAutoCheck = true
            }
            checkUpdate(notifyWhenLatest = false)
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in Routes.TAB_ROUTES) {
                NavigationBar {
                    listOf(
                        Triple(Routes.CAMPUS, Icons.Filled.Home, "首页"),
                        Triple(Routes.WALLET, Icons.Filled.AccountBalanceWallet, "钱包"),
                        Triple(Routes.MINE, Icons.Filled.Person, "我的"),
                    ).forEach { (route, icon, label) ->
                        NavigationBarItem(
                            selected = currentRoute == route,
                            onClick = {
                                navController.navigate(route) {
                                    popUpTo(Routes.CAMPUS) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(icon, contentDescription = label) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(
                    onLoginSuccess = {
                        navController.navigate(Routes.CAMPUS) { popUpTo(0) { inclusive = true } }
                    },
                )
            }

            composable(Routes.CAMPUS) {
                CampusScreen(
                    onOpenBath = { sn, deviceSn, name, type ->
                        val route = "bath?sn=${android.net.Uri.encode(sn)}" +
                            "&deviceSn=${android.net.Uri.encode(deviceSn)}" +
                            "&name=${android.net.Uri.encode(name)}" +
                            "&type=${android.net.Uri.encode(type)}"
                        // launchSingleTop：同一台设备重复点击只保留一个 bath 页面，
                        // 否则每点一次就压入一层，"完成·返回"看起来像在同一个"已完成"页面里循环。
                        navController.navigate(route) { launchSingleTop = true }
                    },
                    onOpenScan = { navController.navigate(Routes.SCAN) { launchSingleTop = true } },
                )
            }

            composable(Routes.SCAN) {
                ScanScreen(
                    onBack = { goBack() },
                    onUseDevice = { snCode, deviceSncode, displayName ->
                        // 扫描识别出的设备同样走网络下单；snCode/deviceSncode 取自 device/info/mac，
                        // 不能用本地归一化 MAC（会被服务端判 306 设备不在线）。
                        val route = "bath?sn=${android.net.Uri.encode(snCode)}" +
                            "&deviceSn=${android.net.Uri.encode(deviceSncode)}" +
                            "&name=${android.net.Uri.encode(displayName)}" +
                            "&type=NET"
                        navController.navigate(route) { launchSingleTop = true }
                    },
                )
            }

            composable(Routes.MINE) {
                MineScreen(
                    onOpenBillDetail = { navController.navigate(Routes.BILL_DETAIL) },
                    onCheckUpdate = { checkUpdate(notifyWhenLatest = true) },
                    onLogout = {
                        // 本地清会话即可触发 LaunchedEffect 跳转；服务端 logout 由仓库尽力而为。
                        container.sessionStore.clear()
                    },
                )
            }

            composable(Routes.BILL_DETAIL) {
                BillDetailScreen(onBack = { goBack() })
            }

            composable(
                route = "bath?sn={sn}&deviceSn={deviceSn}&name={name}&type={type}",
                arguments = listOf(
                    navArgument("sn") { type = NavType.StringType; defaultValue = "" },
                    navArgument("deviceSn") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("type") { type = NavType.StringType; defaultValue = "NET" },
                ),
            ) { entry ->
                BathScreen(
                    sn = entry.arguments?.getString("sn").orEmpty(),
                    deviceSn = entry.arguments?.getString("deviceSn").orEmpty(),
                    deviceName = entry.arguments?.getString("name").orEmpty(),
                    deviceType = entry.arguments?.getString("type").orEmpty(),
                    onBack = { goBack() },
                )
            }

            composable(Routes.WALLET) {
                WalletScreen(onBack = { goBack() })
            }
        }
    }

    // —— 首次使用强制免责声明（须完整输入确认句；未确认前阻塞全部交互） ——
    if (!disclaimerAccepted) {
        DisclaimerDialog(
            requireConfirmTyping = true,
            onAccept = { container.disclaimerStore.markAccepted() },
            onDecline = { (appContext as? Activity)?.finishAffinity() },
        )
    }

    // —— 更新就绪弹窗：安装包已提前静默下载并通过签名校验，点击直接安装。 ——
    readyUpdate?.let { ready ->
        AlertDialog(
            onDismissRequest = {
                dismissedVersion = ready.version
                readyUpdate = null
            },
            title = { Text("新版本 ${ready.version} 已准备就绪") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        ready.notes.ifBlank { "本次更新包含问题修复与体验优化。" },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                    androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
                    Text(
                        "安装包已在本地下载完成并完成签名校验。",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                    )
                    TextButton(
                        onClick = {
                            UpdateManager.setAutoUpdateEnabled(appContext, false)
                            readyUpdate = null
                            Toast.makeText(appContext, "已关闭自动检查更新，可在「我的」中重新开启", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("关闭自动更新")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (!UpdateManager.canInstall(appContext)) {
                            UpdateManager.requestInstallPermission(appContext)
                            return@TextButton
                        }
                        UpdateManager.install(appContext, ready.installUri)
                    },
                ) {
                    Text("立即安装")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        dismissedVersion = ready.version
                        readyUpdate = null
                    },
                ) { Text("稍后") }
            },
        )
    }
}

private const val AUTO_CHECK_INTERVAL_MS = 15 * 60 * 1000L

private object Routes {
    const val LOGIN = "login"
    const val CAMPUS = "campus"
    const val SCAN = "scan"
    const val MINE = "mine"
    const val BILL_DETAIL = "billDetail"
    const val WALLET = "wallet"

    /** 底部菜单栏覆盖的三个主页面。 */
    val TAB_ROUTES = setOf(CAMPUS, WALLET, MINE)
}
