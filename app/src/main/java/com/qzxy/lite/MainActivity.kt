package com.qzxy.lite

import android.net.Uri
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.qzxy.lite.ui.mine.MineScreen
import com.qzxy.lite.ui.login.LoginScreen
import com.qzxy.lite.ui.scan.ScanScreen
import com.qzxy.lite.ui.theme.QzxyTheme
import com.qzxy.lite.ui.wallet.WalletScreen
import com.qzxy.lite.update.UpdateInfo
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

    // —— 应用内更新（GitHub Release）：启动静默检查，有新版弹窗；手动检查入口在"我的"。 ——
    val appContext = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadPct by remember { mutableStateOf<Int?>(null) }
    var downloadedUri by remember { mutableStateOf<Uri?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }

    fun checkUpdate(notifyWhenLatest: Boolean) {
        scope.launch {
            val info = UpdateManager.fetchLatest()
            when {
                info != null && UpdateManager.isNewer(info.version, BuildConfig.VERSION_NAME) ->
                    updateInfo = info
                info != null && notifyWhenLatest ->
                    Toast.makeText(appContext, "当前已是最新版本 ${BuildConfig.VERSION_NAME}", Toast.LENGTH_SHORT).show()
                info == null && notifyWhenLatest ->
                    Toast.makeText(appContext, "检查更新失败，请稍后再试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        delay(1_500)
        checkUpdate(notifyWhenLatest = false)
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

    // —— 更新弹窗：检查 → 下载（进度）→ 开始安装；不跳浏览器。 ——
    updateInfo?.let { info ->
        AlertDialog(
            onDismissRequest = { if (downloadPct == null || downloadedUri != null) updateInfo = null },
            title = { Text("发现新版本 ${info.version}") },
            text = {
                Column {
                    Text(
                        info.notes.ifBlank { "本次更新包含问题修复与体验优化。" },
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                    updateError?.let {
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                        Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                    downloadPct?.let { pct ->
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
                        LinearProgressIndicator(
                            progress = { pct / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 4.dp))
                        Text(
                            if (pct >= 100) "下载完成" else "下载中 $pct%",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                val uri = downloadedUri
                TextButton(
                    enabled = downloadPct == null || uri != null,
                    onClick = {
                        if (uri != null) {
                            UpdateManager.install(appContext, uri)
                        } else if (downloadPct == null) {
                            if (!UpdateManager.canInstall(appContext)) {
                                UpdateManager.requestInstallPermission(appContext)
                                return@TextButton
                            }
                            downloadPct = 0
                            updateError = null
                            scope.launch {
                                try {
                                    val u = UpdateManager.downloadApk(appContext, info) { pct ->
                                        downloadPct = pct
                                    }
                                    downloadedUri = u
                                } catch (t: Throwable) {
                                    updateError = t.message ?: "下载失败"
                                    downloadPct = null
                                }
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            uri != null -> "开始安装"
                            downloadPct != null -> "下载中…"
                            else -> "立即更新"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { updateInfo = null }) { Text("稍后") }
            },
        )
    }
}

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
