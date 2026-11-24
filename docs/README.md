# 趣智校园 Lite (qzxy-lite) 完整代码与协议分析文档

本项目基于对开源项目 `qzxy-lite` 源代码的系统性逆向工程审计与分析，将项目架构、代码行为、安全合规、业务时序、HTTP API 数据包以及蓝牙 BLE 协议分模块独立归档保存。

---

## 📑 文档目录与索引

| 序号 | 文档名称 | 核心内容概述 |
| :---: | :--- | :--- |
| **01** | [01_项目解析与代码行为审计.md](./01_项目解析与代码行为审计.md) | 项目背景、架构设计、代码行为剖析、隐私安全与合规审计结论。 |
| **02** | [02_业务调用时序图.md](./02_业务调用时序图.md) | 包含认证登录、设备扫描、洗浴控制（防重/开阀/轮询/对账/关阀/结算）、钱包充值、自更新等全流程 **Mermaid 时序图**。 |
| **03** | [03_HTTP_API接口与数据包详解.md](./03_HTTP_API接口与数据包详解.md) | 全量 HTTP 接口规范，包含完整请求 Header、Query、Form 报文与 JSON 响应**真实示例数据包**。 |
| **04** | [04_蓝牙BLE协议与数据包示例.md](./04_蓝牙BLE协议与数据包示例.md) | BLE 广播报文解析（KLCXKJ 前缀、MAC 归一化）、GATT 交互模型、云端取蓝牙指令及回传报文示例。 |

---

## 🔍 项目概览

- **项目名称**：趣智校园 Lite (`qzxy-lite`)
- **定位**：校园热水/洗浴场景轻量无广告第三方 Android 客户端（已适配高校 4G 在线水表及脱机蓝牙水表）
- **核心技术栈**：Kotlin 2.0、Jetpack Compose (Material 3)、Retrofit 2、OkHttp 3、kotlinx-serialization、Coroutines & Flow、EncryptedSharedPreferences、Android BLE GATT、Native JNI 签名
- **后端服务端点**：
  - 核心业务网关：`https://v3-api.china-qzxy.cn/`
  - 传统业务网关：`https://wfwapi.china-qzxy.cn/`
  - 应用更新托管：`https://api.github.com/repos/xiaobaphage/qzxy-lite/`
