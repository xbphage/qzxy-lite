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
# 需要 JDK 17 与 Android SDK（compileSdk 35）
./gradlew :app:assembleDebug     # 调试包
./gradlew :app:assembleRelease   # 正式签名包
```

注意：Windows 下请在 **ASCII 路径**构建（中文路径经 gradle.bat 传参可能乱码），
产物在 `app/build/outputs/apk/`，也可直接到 `dist/` 取已构建好的 APK。

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
