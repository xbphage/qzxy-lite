# 趣智校园 Lite（qzxy-lite）

> **当前版本：`v1.1.0`（`versionCode = 8`）**
>
> **【免费开源申明】本软件完全免费且开源（[github.com/xbphage/qzxy-lite](https://github.com/xbphage/qzxy-lite)），无任何收费、内购、付费解锁或代充渠道。如您在任何平台或个人处遇到收费售卖，请立即申请退款并举报！**

- 上游仓库：<https://gitee.com/lindaq1/qzxy-lite>
- 本项目为第三方个人技术学习客户端，**不代表趣智校园或天猫校园官方立场**；
- 仅直连趣智校园官方服务器（`*.china-qzxy.cn`）、淘宝 / 天猫校园官方 H5 mtop 网关（`h5api.m.taobao.com`）及公开 GitHub Release 更新通道，**不额外收集或上传任何个人数据**；
- 相比上游版本，重点适配兼容了**蓝牙脱机水表（无蜂窝网络设备）**全链路通信与纯 Kotlin 签名还原，并在 `v1.1.0` 迁移整合了基于开源项目 `lmc2007/TMallKey`（MIT）裁剪的**天猫校园登录、扫码与直饮水控制**模块；功能极简、稳定性不作保证，实际计费与扣费均以官方为准；
- **仅供个人学习与研究，严禁在任何国内平台传播或分发。**

---

## 核心功能

- **双平台融合与独立账号中心（`v1.1.0`）**：
  - 底部「首页 / 饮水 / 钱包 / 我的」全功能 Tab 自由切换；未登录趣智校园也可无阻断使用天猫饮水及公共设置，「首页 / 钱包」按登录态页内引导登录；
  - 「我的」页面升级为统一双账号中心，同时展示趣智校园与天猫校园登录状态并支持独立登录、切换与退出，未登录趣智时自动隐藏账单/退款等专属模块。
- **天猫校园扫码直饮水（`v1.1.0`）**：
  - 支持**手机号短信验证码（含 WebView 阿里滑块与 `umidToken`）**与**淘宝 H5 网页**两种登录方式，`.confong.cn` 域名 Cookie 隔离与 `EncryptedSharedPreferences` 加密持久化；
  - 集成 **CameraX + ZXing** 原生扫码与手输设备号，识别 `share.confong.cn` 二维码及 `tmxy`/数字资源号，添新机器高亮置顶防误触，支持一键查询设备健康、解锁接水与手动结单（最多记忆 30 台，支持最近使用/自定义排序）。
- **双方式登录与长效保活（趣智）**：支持手机号 + 密码（绕开短信频控）与短信验证码登录；密码 MD5 采用 `EncryptedSharedPreferences`（AES256-GCM/SIV）加密存储，支持周期性自动续期（`user/login/cache/renewal`）与 `errorCode=8` 无感静默重连。
- **蓝牙识别与设备记忆**：复刻 `KLCXKJ` 广播扫描与 MAC 归一化去重规则，自动按云端 `device/info/mac` 区分离线蓝牙水表（`BLE`）与在线水表（`NET`）并本地记忆。
- **双模洗浴控制**：
  - **远程网络模式**：支持 4G / TCP 在线水表远程下发开阀与轮询结算；
  - **蓝牙脱机模式**：手机作为 BLE 中继，读取水表 `0x23` 参数后自动映射云端 `DevID` 与设备大类 `bigTypeId`，以纯 Kotlin 还原加签算法向云端（`v3-api` / `wfwoff`）换取 `downData` 开阀密文，并完整支持断连自动重连关阀（`0x22`）、消费账单采集（`0x85`）、云端上传结算、入库确认（`0x86`）与连点防抖。
- **精准金额与状态同步**：统一兼容服务端 `0.001` 元（毫元整数）与元字符串两种口径；关阀结算自动校验消费额并实时同步刷新 `account/wallet` 账户余额，状态快照跨页面保持。
- **钱包与账单**：账户余额实时展示、官方充值档位与支付宝官方 SDK 直连充值（不经第三方代充/截留）、按月消费/充值账单流水明细查询。
- **应用内静默更新与安全校验（`v1.0.6+`）**：
  - 国内网络优先尝试 `https://gh-proxy.com/` 拉取 GitHub Release 与 APK，失败自动回退 `https://ghproxy.net/` 及 GitHub 直连；
  - 发现新版本后在后台**静默下载**至私有更新目录，通过包名、防降级版本号及**同签名证书 SHA-256 校验**后才弹窗提示一键安装；
  - 下载并校验完成但暂未安装时，**重开应用直接复用本地包提示安装**（无需重复下载同一版本），同时**仍向远端核对是否有更新版本**，并支持在前台切换与开关重开时自动重检，支持在「我的」或更新弹窗中开关自动检查更新；覆盖安装完成后自动清理缓存安装包。
- **强制免费免责弹窗**：首次启动需完整输入确认文本同意使用须知后方可使用，并在「我的」页面保留随时查阅入口。

---

## 技术栈

| 项 | 说明 |
|---|---|
| 语言 / UI | Kotlin 2.0、Jetpack Compose（Material 3）、Navigation Compose |
| 网络 | Retrofit + OkHttp + kotlinx-serialization（趣智 `*.china-qzxy.cn`）、`HttpURLConnection` mtop 网关签名（天猫校园） |
| 扫码与网页认证 | CameraX（`1.4.2`）+ ZXing Core（`3.5.3`）原生识别、Android WebView 滑块/H5 登录与 Cookie 采集 |
| 存储 | `EncryptedSharedPreferences`（趣智与天猫双独立加密会话）、`SharedPreferences`（已用设备、协议确认、更新状态） |
| 蓝牙与安全 | Android BLE GATT + 自定义协议帧编解码、纯 Kotlin MD5/AES 签名实现、R8 树摇与资源缩减 |
| 更新 | GitHub Releases API + `gh-proxy.com` 优先镜像 + `FileProvider` 本地签校验 + `PackageInstaller` |
| 构建 | Gradle 9.3.1 / AGP 8.7.3 / JDK 17+ / compileSdk 36 / minSdk 26 |

---

## 构建与测试

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 36）
./gradlew :app:testDebugUnitTest   # 运行本地 JVM 单元测试
./gradlew :app:assembleDebug       # 调试包（默认调试签名）
./gradlew :app:assembleRelease     # 正式发布包（无签名裸包）
```

> Windows 下请在**纯 ASCII 路径**构建（中文路径经 `gradlew.bat` 传参可能导致编码问题）。构建输出位置为 `app/build/outputs/apk/debug/` 与 `app/build/outputs/apk/release/`（仓库早期留存参考包位于 `dist/app-release-unsigned.apk`）。

---

## CI/CD 自动构建与发布签名配置（GitHub Actions）

本项目已配置 GitHub Actions 流水线（[.github/workflows/release.yml](.github/workflows/release.yml)）。推送 `v*` 标签或手动触发 `workflow_dispatch` 时，会自动执行单元测试、编译 Release APK 并用配置的证书完成签名发布。

### 1. 生成签名密钥库（`.jks`）

在仓库目录之外执行（切勿提交 `.jks` 文件或密码到 Git）：

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias qzxy-release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass 你的密码 \
  -keypass 你的密码 \
  -dname "CN=qzxy-lite, OU=Dev, O=Personal, L=City, ST=Province, C=CN"
```

> 后续版本更新务必保持使用**同一份 `.jks`**，客户端应用内更新校验及系统覆盖安装均要求签名证书一致。

### 2. 编码为 Base64 并配置 GitHub Secrets

- **Windows PowerShell**：
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
  ```
- **Linux / macOS**：
  ```bash
  base64 -w 0 release.jks
  # macOS: base64 -i release.jks | pbcopy
  ```

在 GitHub 仓库 **Settings → Secrets and variables → Actions → New repository secret** 依次添加：

| Secret 变量名 | 说明 |
|---|---|
| `KEYSTORE_BASE64` | 密钥库 `.jks` 的 Base64 字符串 |
| `KEYSTORE_PASSWORD` | 密钥库密码（`-storepass`） |
| `KEY_ALIAS` | 密钥别名（`-alias`） |
| `KEY_PASSWORD` | 私钥密码（`-keypass`） |

### 3. 触发构建与发布

```bash
git add .
git commit -m "chore: release v1.1.0"
git tag v1.1.0
git push origin master --tags
```

推送后云端将自动：
1. 配置 JDK 21 与 Android SDK 36（`platform-tools`、`platforms;android-36`、`build-tools;36.0.0`）；
2. 运行 `./gradlew testDebugUnitTest assembleRelease --no-daemon`；
3. 若配置了签名 Secrets，调用 `apksigner` 完成 **V1 + V2 + V3** 签名并验证；
4. 上传构建产物并在 `v*` 标签触发时自动发布 GitHub Release（如 `qzxy-lite-v1.1.0.apk`）。

---

## 目录结构

```text
app/src/
├── main/java/com/
│   ├── klcxkj/jni/
│   │   └── JniUtils.kt           # 官方双重加盐 MD5 签名与 AES 加解密纯 Kotlin 还原
│   └── qzxy/lite/
│       ├── core/                 # 常量、响应封装、Crypto（MD5/Hex）、Money（0.001元换算）、诊断日志
│       ├── data/
│       │   ├── api/              # Auth/Campus/Device/Order/Wallet/Bill Retrofit 接口
│       │   ├── network/          # OkHttp/Retrofit 装配、公共参数拦截器、会话失效自动重连
│       │   ├── model/            # 领域与接口数据模型（弹性解析与毫元转换）
│       │   ├── repository/       # 登录、校园、洗浴双模、钱包、账单仓库
│       │   ├── session/          # EncryptedSharedPreferences 会话与凭据存储
│       │   ├── device/           # 已绑定设备本地存储
│       │   ├── bath/             # 进程级洗浴状态快照存储
│       │   └── legal/            # 免责声明确认状态存储
│       ├── tmall/                # 天猫校园 mtop 协议客户端、饮水 API、短信+滑块登录、加密会话与常用水机存储
│       ├── ble/                  # BLE 扫描器、帧编解码器、GATT 水表客户端
│       ├── mqtt/                 # 可选 MQTT 客户端模块（默认未开启）
│       ├── update/               # GitHub Release 静默预下载、gh-proxy 加速与签名校验更新器
│       └── ui/                   # Compose 界面与 ViewModel（login/campus/water/scan/bath/wallet/bill/mine/common/diag）
└── test/java/com/qzxy/lite/      # BLE 编解码/加签、天猫饮水、金额模型与更新器本地 JVM 单元测试
```

---

## 版本记录

| 版本 | 说明 |
|---|---|
| **v1.1.0** | 迁移整合天猫校园登录（手机号短信+WebView 滑块 / 淘宝 H5）、CameraX+ZXing 扫码与直饮水控制（含扫码后置顶高亮防误触）；改造「我的」为双平台账号中心并支持未登录趣智时在「首页/钱包」页内无阻断引导 |
| **v1.0.7** | 正式包启用 R8 代码树摇与资源缩减优化，大幅压缩安装包体积 |
| **v1.0.6** | 修复已发现/缓存新版本后不再继续向远端检查更新版本的 Bug（支持复用本地包同时检出更高版本、后台切回定时重检与重新启用自动更新即刻检查）；新增自动检查更新开关；全局可滚动页面与弹窗补全右侧滚动条及底部渐变提示并重构免责弹窗布局 |
| **v1.0.5** | 应用内更新支持 `https://gh-proxy.com/` 国内优先加速、发现新版后台静默下载与本地 APK 签名/SHA-256 校验、重开应用免重复拉取；修复关闸结算消费与余额显示（毫元换算 + 实时钱包刷新）及 `BathDevice` 字段解析；新增首次启动输入确认强制免费免责声明弹窗；优化 BLE 断连重连关阀/收发防丢帧、HTTP 连接池复用及 GitHub Actions SDK 36 自动化测试与构建 |
| v1.0.0 | 界面精简、应用内更新、洗澡状态跨页面保持、金额单位修复 |
| v0.1.1 | 更新功能接入 Release 分发 |
| v0.1.0 | 首个可运行版本 |

---

## 免责声明与使用须知

1. **免费开源声明**：本软件（`qzxy-lite`）为完全免费的个人学习开源项目，没有任何收费或内购渠道；**如在任何渠道遇到收费售卖，请立即退款并举报！**
2. **非官方声明**：本项目为第三方个人学习项目，不代表趣智校园官方立场。
3. **隐私说明**：仅直连官方服务器与公开 GitHub 更新接口，不额外获取、收集或上传任何个人数据。
4. **功能限制**：不保证运行稳定性，目前仅「洗澡」功能经过实际测试，不建议使用其他功能；实际计费与扣费均以官方为准。
5. **禁止传播**：仅供个人学习，严禁在任何国内平台传播或分发。

继续使用即代表您同意以上内容并自愿承担相关风险；如有异议，请立即停止使用并卸载本软件。
