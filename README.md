# 趣智校园 Lite（qzxy-lite）

一个用于校园热水/洗浴场景的轻量第三方 Android 客户端，基于 Kotlin + Jetpack Compose 从零实现，
通过 GitHub Release 实现应用内自更新，支持网络联网水表与蓝牙脱机（无蜂窝网络）水表双模通信。

> 本项目仅供学习研究与个人使用，请勿用于商业用途；「趣智校园」名称及相关服务归原厂所有。

## 功能

- **登录**：短信验证码 / 密码两种方式，会话本地加密存储
- **设备**：蓝牙扫描识别（KLCXKJ 前缀规则、MAC 归一化）+ 已用设备本地记忆
- **洗浴控制**：
  - 远程网络模式：支持 4G/NB-IoT 在线水表远程下发开阀；
  - 蓝牙脱机模式：支持校园无蜂窝脱机水表（解决 306 错误），手机充当 BLE 中继自动换取云端加签密文并开阀结账。
- **状态跟随**：洗澡状态跨页面保持，并与服务端周期对账（设备被占用 / 已关阀可及时反映）
- **钱包**：余额查询、充值档位展示、拉起支付宝官方支付
- **账单**：按月消费 / 充值流水查询
- **应用内更新**：对接 GitHub Release，弹窗提示 → 下载到系统下载目录 → 拉起安装 → 安装后自动清理

## 技术栈

| 项 | 说明 |
|---|---|
| 语言 / UI | Kotlin 2.0、Jetpack Compose（Material 3）、Navigation Compose |
| 网络 | Retrofit + OkHttp + kotlinx-serialization，统一响应封装与公共参数拦截器 |
| 存储 | EncryptedSharedPreferences（会话）、SharedPreferences（设备列表等） |
| 更新 | GitHub Releases OpenAPI + MediaStore 下载目录 + PackageInstaller，安装完成广播自清理 |
| 构建 | Gradle 9.3 / AGP 8.7.3 / JDK 17+ / compileSdk 36 / minSdk 26 |

## 构建与运行

```bash
# 需要 JDK 17+ 与 Android SDK（compileSdk 36）
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 正式发布包（无签名裸包）
```

注意：Windows 下请在 **ASCII 路径**构建（中文路径经 gradle.bat 传参可能乱码），
构建产物在 `app/build/outputs/apk/release/`，已编译好的无签名版本也放置在 `dist/app-release-unsigned.apk`。

## CI/CD 自动构建与发布签名配置（GitHub Actions）

本项目已集成 GitHub Actions 自动化流水线（[.github/workflows/release.yml](.github/workflows/release.yml)）。
当推送到以 `v` 开头的 Tag 时，GitHub 会自动编译并在 Releases 中发布带有正式数字签名的 APK。

由于 Android 系统的安全限制，**未签名的 APK 无法在手机上直接安装或进行应用内自动更新**。为了让云端自动签名并分发可以直接安装的 APK，请按照以下步骤配置 GitHub Secrets：

### 1. 准备签名密钥库（Keystore）

如果你本地已有常用的 Android 签名密钥库（`.jks` 或 `.keystore` 文件），可直接使用。
如果没有，可以在本地终端（PowerShell 或 Bash）运行以下命令生成一个新的密钥库文件：

```bash
keytool -genkeypair -v \
  -keystore release.jks \
  -alias qzxy-release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass 你的密码123 \
  -keypass 你的密码123 \
  -dname "CN=qzxy-lite, OU=Dev, O=Personal, L=City, ST=Province, C=CN"
```

### 2. 将密钥库转为 Base64 字符串

- **Windows PowerShell**：
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Set-Clipboard
  ```
  *(命令执行后，Base64 字符串已自动复制到剪贴板)*

- **Linux / macOS**：
  ```bash
  base64 -w 0 release.jks
  # 或者 macOS:
  # base64 -i release.jks | pbcopy
  ```

### 3. 在 GitHub 仓库中配置 Secrets

1. 打开 GitHub 仓库页面，点击顶部 **Settings**。
2. 在左侧菜单找到 **Secrets and variables** -> 点击 **Actions**。
3. 点击绿色按钮 **New repository secret**，依次添加以下 4 个环境变量：

| Secret 变量名 | 内容说明 | 示例 |
|---|---|---|
| `KEYSTORE_BASE64` | 步骤 2 复制的密钥库 Base64 字符串 | `MIIKu...（长文本）` |
| `KEYSTORE_PASSWORD` | 密钥库文件的密码 | `你的密码123` |
| `KEY_ALIAS` | 密钥别名（Alias） | `qzxy-release` |
| `KEY_PASSWORD` | 该密钥别名的私钥密码（若与密钥库相同可填相同值） | `你的密码123` |

### 4. 触发自动构建与发布

配置好上述 Secrets 后，发布新版本只需在本地终端打 Tag 并推送：

```bash
# 1. 提交本地所有修改
git add .
git commit -m "chore: release version v1.0.0"

# 2. 打标签并推送到 GitHub
git tag v1.0.0
git push origin master --tags
```

推送后，GitHub Actions 会自动：
1. 检出代码并配置 JDK 21 与 Android SDK；
2. 执行 `./gradlew assembleRelease` 编译出 APK；
3. 读取 Secrets 解码密钥库，使用 Android SDK 原生 `apksigner` 完成 **V1 + V2 + V3** 强签名并校验签名有效性；
4. 自动在 GitHub Releases 发布 `qzxy-lite-v1.0.0.apk`。

手机安装该版本后，未来每次发布新 Tag，App 都能检测到并**一键直接完成覆盖更新**！

---

## 应用内更新说明

- 启动时自动检查最新 Release，有新版本则弹窗提示，可一键下载安装；
- 下载走 OkHttp 流式写入系统下载目录（Android 10+ 使用 MediaStore）；
- 覆盖安装完成后自动删除下载的安装包；
- 仓库保持公开即可免令牌检查更新。

## 目录结构

```text
app/src/main/java/com/qzxy/lite/
├── core/          # 常量、统一响应、金额单位换算
├── data/
│   ├── api/       # Retrofit 接口定义
│   ├── network/   # OkHttp/Retrofit 装配、公共参数拦截器
│   ├── repository/# 业务仓库（登录/设备/订单/钱包/账单）
│   ├── session/   # 会话与加密存储
│   ├── device/    # 本地设备库
│   └── bath/      # 洗浴会话快照（跨页面状态）
├── ble/           # BLE 扫描与命令抽象、脱机水表协议编解码与通信客户端
├── jni/           # Native JNI 动态加签 (libklcxkjencry.so)
├── update/        # GitHub Release 应用内自更新与镜像分发
└── ui/            # Compose 页面与 ViewModel（login/campus/scan/bath/wallet/bill/mine）
```

## 版本

| 版本 | 说明 |
|---|---|
| v1.0.0 | 界面精简、应用内更新、洗澡状态跨页面保持、金额单位修复 |
| v0.1.1 | 更新功能接入 Gitee Release |
| v0.1.0 | 首个可运行版本 |

## 免责声明

本项目为个人学习性质的非官方客户端，不收集、不分发任何用户数据；请仅使用本人账号，
遵守目标服务的使用条款，因使用本项目产生的任何问题由使用者自行承担。
