# 03_HTTP API 接口与数据包详解

本文档基于 `qzxy-lite` 源码、脱壳 DEX 与真实环境抓包日志，对客户端涉及的所有 HTTP API 接口规范、请求头、公共注入参数、各接口入参字段、出参 JSON 字段进行**逐字段（Every Single Field）详尽剖析与解释**。

---

## 一、 公共通信规范与全局注入参数

### 1. 域名网关与基础配置
*   **主业务网关**：`https://v3-api.china-qzxy.cn/`（几乎所有用户认证、水表开闭、充值、钱包接口均在此网关）
*   **传统老接口网关**：`https://wfwapi.china-qzxy.cn/`
*   **脱机蓝牙专有网关**：`https://wfwoff.china-qzxy.cn/`（官方 App 脱机蓝牙水表专用接口网关，端点如 `/offlineOrder/downRate/bluetooth/rateOrder`、`/offlineOrder/upload/bluetooth/data`）
*   **H5 页面活动域（非 API 网关）**：`https://app-cold-water.china-qzxy.cn/`（用于 WebView 展示 `/billDetail` 账单明细网页与 `/coupon` 优惠券，并非 RESTful 数据接口）
*   **客户端版本标识**：`6.5.28`
*   **客户端操作系统**：`android`

### 2. 全局请求头字段详解 (Headers)
由客户端网络拦截器 [CommonParamsInterceptor.kt](file:///d:/Users/xiaobaphage/Downloads/qzxy-lite-master/qzxy-lite-master/app/src/main/java/com/qzxy/lite/data/network/CommonParamsInterceptor.kt) 自动在每一个出站 HTTP 请求中注入：

| 请求头字段名 | 示例值 | 字段含义与解析说明 |
| :--- | :--- | :--- |
| **User-Agent** | `Mozilla/5.0 (Linux; Android 14; Mobile)...` | 客户端设备标识，模拟官方 Android App 运行环境。 |
| **Accept** | `application/json; charset=utf-8` | 客户端期望接收服务端返回的数据格式（UTF-8 编码的 JSON）。 |
| **Content-Type** | `application/x-www-form-urlencoded; charset=utf-8` | 请求体格式。官方接口严禁随意改用 `application/json`，否则会触发服务端鉴权签名校验拦截报错。 |
| **Config-Project** | `10086` | 当前用户所绑定的高校校区 ID。服务端借此做校园租户隔离。 |
| **Config-Keys** | `module_list,advertise_type,question_list...` | 客户端需要服务端拉取的动态业务模块配置列表项。 |

---

### 3. 全局公共注入参数详解 (Common Parameters)
除公开验证码登录链路外，几乎所有 GET 请求（作为 Query 参数）或 POST 表单（作为 Form 字段）均被拦截器注入以下字段：

| 参数字段名 | 类型 | 示例值 | 字段含义与解析说明 |
| :--- | :---: | :--- | :--- |
| **`loginCode`** | String | `c4ca4238a0b923820dcc509a6f75849b` | 用户登录成功后服务端签发的会话访问令牌（Token）。校验会话合法性及当前登录态。 |
| **`userId`** | String | `1234567` | 用户的全局唯一数字 ID。 |
| **`accountId`** | String | `8901234` | 用户在当前学校或热水业务下属的虚拟账户数字 ID。 |
| **`projectId`** | String | `10086` | 用户所绑定的高校校区编号 ID（用于关联计费费率与设备归属）。 |
| **`telephone`** | String | `13800138000` | 用户绑定的 11 位手机号码（部分旧版接口读取该字段）。 |
| **`telPhone`** | String | `13800138000` | 用户绑定的 11 位手机号码（部分新版接口参数名采用驼峰小写，拦截器双发兼容）。 |
| **`phoneSystem`**| String | `android` | 移动操作系统类型，固定为 `"android"`。 |
| **`version`** | String | `6.5.28` | 模拟官方 App 版本号，服务端用于版本兼容性判定。 |

---

### 4. 统一响应格式外层字段详解 (Response Envelope)
服务端所有返回均使用标准的统一顶层 JSON 结构：

```json
{
  "errorCode": 0,
  "message": "操作成功",
  "data": { ... }
}
```

| 字段名 | 类型 | 示例值 | 字段含义与解析说明 |
| :--- | :---: | :--- | :--- |
| **`errorCode`** | Int | `0` | 业务状态码：<br/>- `0`: 成功；<br/>- `-1`: 基础参数校验缺失或非法；<br/>- `-2`: 数据逻辑异常；<br/>- `5`: HTTP 请求方法错误（如要求 POST 却发了 GET）；<br/>- `8`: 登录令牌过期失效，需重新登录；<br/>- `12`: 用户名或密码错误；<br/>- `306`: **设备不在线**（水表未建立 TCP 长连接，需降级到蓝牙 BLE 模式）；<br/>- `307`: **设备使用中**（当前设备已有正在出水的订单）。 |
| **`message`** / **`errorMessage`** | String | `"操作成功"` | 服务端给出的业务提示信息，出错时为错误原因描述。 |
| **`data`** | Object / Array / String / Null | `{ ... }` | 具体的业务数据载荷，结构因接口而异。当 `errorCode != 0` 时通常为 `null`。 |

---

## 二、 认证与用户接口 (AuthApi)

### 1. 发送短信验证码 (`GET /user/verification/code/get`)
*   **接口地址**：`GET /user/verification/code/get`
*   **功能说明**：向指定手机号发送短信验证码，防刷签名依赖特定手机号切片加盐 MD5。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`secret`** | String | 是 | `d8578edf8458ce06fbc5bb76a58c5ca4` | 防刷校验签名：$\text{MD5}(\text{telephone}[0..2] + \text{telephone}[7..10] + \text{"klcx"})$ 得到 32 位小写 Hex。例如手机号 `13800138000`，取前 3 位 `138`、后 4 位 `8000` 拼上 `klcx` $\rightarrow$ `1388000klcx` 的 MD5。 |
| **`telephone`** | String | 是 | `13800138000` | 接收短信验证码的 11 位中国大陆手机号码。 |
| **`typeId`** | Int | 是 | `3` | 验证码业务类型：`3` 代表用户登录/注册短信。 |
| **`platform`** | Int | 是 | `1` | 客户端平台标识：`1` 代表 Android 移动端。 |
| **`phoneSystem`**| String | 否 | `android` | 系统标识。 |
| **`version`** | String | 否 | `6.5.28` | 客户端版本。 |

#### 请求示例数据包
```http
GET /user/verification/code/get?secret=d8578edf8458ce06fbc5bb76a58c5ca4&telephone=13800138000&typeId=3&platform=1&phoneSystem=android&version=6.5.28 HTTP/1.1
Host: v3-api.china-qzxy.cn
Accept: application/json; charset=utf-8
```

#### 响应示例数据包
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 0,
  "message": "短信验证码发送成功",
  "data": null
}
```

#### 响应 JSON 字段解释表
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`errorCode`** | Int | `0` | `0` 表示短信网关已成功受理并提交运营商发送。 |
| **`message`** | String | `"短信验证码发送成功"` | 状态描述文本。 |
| **`data`** | Null | `null` | 无业务返回载荷。 |

---

### 2. 手机验证码登录 / 注册 (`POST /user/registerAndLogin`)
*   **接口地址**：`POST /user/registerAndLogin`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：校验短信验证码，若手机号已注册则直接登录，未注册则自动开户并登录。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`telephone`** | String | 是 | `13800138000` | 登录手机号。 |
| **`smsCode`** | String | 是 | `888888` | 用户收到的 6 位短信数字验证码。 |
| **`type`** | Int | 是 | `5` | 登录鉴权方式代码：`5` 代表短信验证码快捷登录。 |
| **`phoneSystem`**| String | 否 | `android` | 移动客户端操作系统标识。 |
| **`version`** | String | 否 | `6.5.28` | 客户端版本号。 |

#### 请求示例数据包
```http
POST /user/registerAndLogin HTTP/1.1
Host: v3-api.china-qzxy.cn
Content-Type: application/x-www-form-urlencoded; charset=utf-8

telephone=13800138000&smsCode=888888&type=5&phoneSystem=android&version=6.5.28
```

#### 响应示例数据包
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 0,
  "message": "登录成功",
  "data": {
    "loginCode": "c4ca4238a0b923820dcc509a6f75849b",
    "telephone": "13800138000",
    "userId": 1234567,
    "userAccount": {
      "userId": 1234567,
      "accountId": 8901234,
      "projectId": 10086,
      "projectName": "示例大学",
      "name": "张三",
      "gender": 1,
      "accountRealMoney": 15500,
      "accountGivenMoney": 2000
    }
  }
}
```

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`loginCode`** | String | `"c4ca4238..."` | **核心身份凭据 Token**。后续所有需要授权的 HTTP 请求均须带上此参数。 |
| **`telephone`** | String | `"13800138000"` | 当前登录用户绑定的手机号码。 |
| **`userId`** | Long / String | `1234567` | 用户的全局唯一用户编号。 |
| **`userAccount`** | Object | `{...}` | 用户绑定的校园卡/热水账户实体对象。 |
| **`userAccount.userId`** | Long / String | `1234567` | 账户从属的用户 ID。 |
| **`userAccount.accountId`**| Long / String | `8901234` | **核心账户 ID**。后续设备控制、钱包充值、账单结算时对应扣费的账户。 |
| **`userAccount.projectId`**| Long / String | `10086` | **核心高校 ID**。当前账户绑定的学校或校区代码。 |
| **`userAccount.projectName`**| String | `"示例大学"` | 当前所绑定高校的学校名称。 |
| **`userAccount.name`** | String | `"张三"` | 学生真实姓名或系统开户人名。 |
| **`userAccount.gender`** | Int | `1` | 性别：`1` 男，`2` 女，`0` 未知。 |
| **`userAccount.accountRealMoney`** | Long / String | `15500` | 真实充值本金余额。**注意：服务端整数数值单位为 0.001 元（厘）**，`15500` 表示 15.500 元。 |
| **`userAccount.accountGivenMoney`**| Long / String | `2000` | 活动赠送金余额。单位同为 0.001 元，`2000` 表示 2.000 元。 |

---

### 3. 密码登录 (`POST /user/login`)
*   **接口地址**：`POST /user/login`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：在受限网络环境或短信频控限制下，使用已设置的密码直接登录。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`telephone`** | String | 是 | `13800138000` | 手机号。 |
| **`password`** | String | 是 | `e10adc3949ba59abbe56e057f20f883e` | 明文密码经过标准 MD5 小写运算转换出的 32 位 Hex 哈希值。 |
| **`type`** | Int | 是 | `0` | 鉴权模式代码：`0` 代表普通账号密码验证模式。 |
| **`identifier`** | String | 否 | `""` | 硬件或第三方唯一绑定识别码，未配置时传空串。 |
| **`phoneSystem`**| String | 否 | `android` | 系统标识。 |
| **`version`** | String | 否 | `6.5.28` | 客户端版本。 |

#### 请求示例数据包
```http
POST /user/login HTTP/1.1
Host: v3-api.china-qzxy.cn
Content-Type: application/x-www-form-urlencoded; charset=utf-8

telephone=13800138000&password=e10adc3949ba59abbe56e057f20f883e&type=0&identifier=&phoneSystem=android&version=6.5.28
```

#### 响应 JSON 字段解释表
*与 `registerAndLogin` 返回格式完全一致，参见上述字段表。若密码错误，返回 `errorCode: 12, message: "用户名或密码错误"`。*

---

### 4. 获取用户详情 (`GET /user/info`)
*   **接口地址**：`GET /user/info`
*   **功能说明**：拉取个人资料、当前绑定学校、头像以及最新钱包余额。

#### 请求参数字段解释表
*依赖公共参数自动注入：`loginCode`, `userId`, `accountId`, `projectId`, `telephone`, `phoneSystem`, `version`。*

#### 响应示例数据包
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 0,
  "message": "success",
  "data": {
    "userId": "1234567",
    "accountId": "8901234",
    "projectId": "10086",
    "telephone": "13800138000",
    "telPhone": "13800138000",
    "name": "张三",
    "nickname": "小张同学",
    "face": "https://oss.../avatar.jpg",
    "userAccount": {
      "accountRealMoney": "15.50",
      "accountGivenMoney": "2.00",
      "projectName": "示例大学"
    }
  }
}
```

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`userId`** | String | `"1234567"` | 用户 ID。 |
| **`accountId`** | String | `"8901234"` | 绑定的主要扣费账户 ID。 |
| **`projectId`** | String | `"10086"` | 所属高校代码 ID。 |
| **`telephone`** / **`telPhone`**| String | `"13800138000"` | 绑定的手机号。 |
| **`name`** | String | `"张三"` | 实名制姓名。 |
| **`nickname`** | String | `"小张同学"` | 昵称。 |
| **`face`** | String | `"https://..."` | 用户头像图片 URL。 |
| **`userAccount.accountRealMoney`**| String | `"15.50"` | 当前账户本金可用余额（字符串形式，单位为元）。 |
| **`userAccount.accountGivenMoney`**| String | `"2.00"` | 当前账户赠送金余额（字符串形式，单位为元）。 |
| **`userAccount.projectName`**| String | `"示例大学"` | 所属学校全称。 |

---

## 三、 设备与位置接口 (DeviceApi & CampusApi)

### 1. 根据 MAC 地址查询设备详情 (`GET /device/info/mac`)
*   **接口地址**：`GET /device/info/mac`
*   **功能说明**：手机扫描水表二维码或通过 BLE 发现附近水表 MAC 地址后，请求云端以获得水表的正式设备名、通信编号及设备型号。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`macAddress`** | String | 否 | `00%3A7F%3A0E%3AD9%3AE8%3A4A` | 带冒号且经过 URL 编码的标准物理 MAC 地址（大写）。 |
| **`identifyCode`**| String | 否 | `""` | 二维码铭牌中的识别代码（与 macAddress 二选一）。 |
| **`isNew`** | String | 是 | `"1"` | 协议兼容标志：固定传 `"1"`（标识支持新版水表）。 |
| **`isHaier`** | String | 是 | `"0"` | 设备品牌标识：`"0"` 非海尔；`"1"` 海尔定制设备。 |
| **`projectId`** | String | 是 | `"10086"` | （公共注入）当前校区 ID。 |

#### 请求示例数据包
```http
GET /device/info/mac?macAddress=00%3A7F%3A0E%3AD9%3AE8%3A4A&isNew=1&isHaier=0&loginCode=c4ca4238...&projectId=10086 HTTP/1.1
Host: v3-api.china-qzxy.cn
Config-Project: 10086
```

#### 响应示例数据包
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 0,
  "message": "success",
  "data": {
    "DevName": "热水表-2号公寓-2单元-6层-625",
    "deviceName": "2-2-625水表",
    "snCode": "C47F0ED9E84A",
    "deviceSncode": "C47F0ED9E84A",
    "macAddress": "00:7F:0E:D9:E8:4A",
    "devMac": "00:7F:0E:D9:E8:4A",
    "deviceType": "1",
    "deviceModel": "TCP_BATH_V2",
    "status": "0",
    "usingStatus": "0",
    "isOnline": "1",
    "isBle": "1"
  }
}
```

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`DevName`** | String | `"热水表-2号公寓..."` | **后端官方规范设备全名**。通常包含楼栋、单元、楼层及房间号，客户端界面主要展示该名称。 |
| **`deviceName`** | String | `"2-2-625水表"` | 水表简称或别名。 |
| **`snCode`** | String | `"C47F0ED9E84A"` | **核心设备通信 SN**。将 MAC 冒号去掉转大写，用于后续指令下发参数。 |
| **`deviceSncode`**| String | `"C47F0ED9E84A"` | 备用设备通信编号，某些接口要求与 snCode 保持一致同发。 |
| **`macAddress`** | String | `"00:7F:0E:D9:E8:4A"`| 带冒号标准格式的蓝牙物理 MAC 地址。 |
| **`devMac`** | String | `"00:7F:0E:D9:E8:4A"`| 硬件物理 MAC。 |
| **`deviceType`** | String | `"1"` | 设备业务大类：`"1"` 代表热水淋浴设备，`"2"` 代表开水机，`"3"` 代表洗衣机，`"4"` 代表吹风机。 |
| **`deviceModel`** | String | `"TCP_BATH_V2"` | 硬件型号代码。若包含 `BLE` 或在现场无网络，则属于脱机水表。 |
| **`status`** | String | `"0"` | 设备运行状态：`"0"` 空闲可用；`"1"` 故障或停用中。 |
| **`usingStatus`** | String | `"0"` | 用水状态：`"0"` 未被占用；`"1"` 正在用水中。 |
| **`isOnline`** | String | `"1"` | 服务端检测到的设备 TCP 长连接状态：`"1"` 在线；`"0"` 离线（无蜂窝模块的水表恒为 0 或因无心跳报 306）。 |
| **`isBle`** | String | `"1"` | 蓝牙支持标识：`"1"` 支持手机蓝牙 BLE 直连控制。 |

---

### 2. 按区域拉取水表列表 (`GET /device/bath/list/area`)
*   **接口地址**：`GET /device/bath/list/area`
*   **功能说明**：用户根据所选宿舍楼或浴室区域，拉取该区域下挂载的所有水表设备列表。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`areaId`** | String | 否 | `""` 或 `"302"` | 区域/楼层节点 ID。传空字符串代表拉取当前学校下所有默认区域水表。 |
| **`projectId`** | String | 是 | `"10086"` | （公共注入）当前校区 ID。 |

#### 响应 JSON 字段解释表 (`data` 数组项)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`DevName`** | String | `"热水表-1栋-101"` | 浴室水表完整名称。 |
| **`snCode`** | String | `"C47F0ED9E801"` | 设备 SN（用于后续开阀）。 |
| **`status`** | String | `"0"` | 设备状态（`"0"` 正常，`"1"` 占用或不可用）。 |
| **`usingStatus`** | String | `"0"` | 设备用水占用状态。 |

---

## 四、 洗浴水表核心控制接口 (OrderApi)

### 1. 查询当前使用中的订单 (`POST /order/tcpDevice/query/rateOrder/using`)
*   **接口地址**：`POST /order/tcpDevice/query/rateOrder/using`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：在开阀前进行防重复检查；同时在出水过程中充当心跳探测，若发现已有未完结订单则返回对应订单号。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`xfModel`** | String | 是 | `"0"` | 消费模式模式代码：`"0"` 为默认淋浴洗浴计费模型。 |
| **`snCode`** | String | 是 | `"C47F0ED9E84A"` | 水表 SN 编号（无冒号大写 MAC）。 |
| **`deviceSncode`**| String | 否 | `"C47F0ED9E84A"` | 设备 SN 冗余字段（原版 App 保持与 snCode 一致同发）。 |

#### 请求示例数据包
```http
POST /order/tcpDevice/query/rateOrder/using HTTP/1.1
Host: v3-api.china-qzxy.cn
Content-Type: application/x-www-form-urlencoded; charset=utf-8

xfModel=0&snCode=C47F0ED9E84A&deviceSncode=C47F0ED9E84A&loginCode=c4ca4238...&projectId=10086
```

#### 响应示例数据包 A (设备正在使用中，返回运行中订单)
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 307,
  "message": "当前设备正在用水中",
  "data": {
    "orderNo": "202611231930450123"
  }
}
```

#### 响应 JSON 字段解释表
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`errorCode`** | Int | `307` / `0` | `307`: 设备正被占用；`0`: 设备当前处于空闲状态。 |
| **`message`** | String | `"当前设备正在用水中"` | 提示文本。 |
| **`data.orderNo`**| String | `"202611231930450123"` | **进行中的订单流水号**。客户端凭此直接进入“用水中/等待关阀”界面。 |

---

### 2. 远程网络开阀下单 (`POST /order/tcpDevice/downRate/rateOrder`)
*   **接口地址**：`POST /order/tcpDevice/downRate/rateOrder`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：尝试通过云端 TCP 长连接直接命令水表开阀（适用于配备 4G/NB-IoT 模块的联网水表）。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`xfModel`** | String | 是 | `"0"` | 消费模式：`"0"` 默认洗浴。 |
| **`snCode`** | String | 是 | `"C47F0ED9E84A"` | 水表 SN 码。 |
| **`deviceSncode`**| String | 否 | `"C47F0ED9E84A"` | 设备 SN 码。 |

#### 响应示例与状态码判定
- **成功受理**：返回 `errorCode: 0, message: "开阀指令已成功下发"`，随后客户端轮询开阀结果；
- **错误 306（设备不在线）**：说明该水表为**脱机无网水表**，云端无 TCP 长连接，必须降级转入下述 **2.1 蓝牙脱机开阀接口**；
- **错误 307（设备使用中）**：当前水表已被其他学生或未结订单占用。

---

### 2.1 蓝牙脱机水表开阀下单接口 (`POST /order/downRate/bluetooth/rateOrder`)
*   **接口地址**：`POST /order/downRate/bluetooth/rateOrder`
*   **备用端点**：`POST https://wfwoff.china-qzxy.cn/offlineOrder/downRate/bluetooth/rateOrder`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：将手机蓝牙读取到的水表硬件参数及动态随机数 Nonce 提交给云端，云端验证签名及账户余额后，签发包含开阀密钥的 48 字节密文 `downData`。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`deviceId`** | String | 是 | `"19931"` | **云端登记的设备 ID (`DeviceInfo.DevID`)**。由 `device/info/mac` 接口根据设备 MAC 查询获得。**注意**：切勿直接传入水表蓝牙 0x23 响应中的内部物理芯片 ID（如 `20863`），否则云端因设备库中无此 ID 会直接报 `519 找不到设备`。官方 App (`BathingBleActivity.java`) 中显式传入 `this.k0.getDevID()` 并以此参与签名计算。 |
| **`macAddress`** | String | 是 | `"C47F0E3E59C5"` | 水表无冒号大写物理 MAC 地址。 |
| **`macType`** | String | 是 | `"0001"` | 水表分类硬件标识（从蓝牙 0x23 响应中提取，通常为 `"0001"` 或 `"0000"`）。 |
| **`bigTypeId`** | String | 是 | `"4"` | 业务大类代码：官方沐浴设备为 `"4"` (`Dsbtypeid=4`)，饮水为 `"5"`，洗衣为 `"6"`。若传错为 `"1"` 会与后台设备类型不匹配。 |
| **`smallTypeId`**| String | 是 | `"1"` | 业务小类代码：官方通常为 `"1"` (`DevTypeID`)。 |
| **`protocolType`**| String| 是 | `"10"` | 脱机计费协议版本代码。从蓝牙 0x23 响应提取，当前主流为第 10 代协议。 |
| **`randomNumber`**| String| 是 | `"e1000000"` | **核心安全随机挑战字 (Nonce)**。从水表 0x23 响应偏移 `0x1E-0x21` 读取，每次开机动态随机产生，防止下发重放攻击。 |
| **`signature`** | String | 是 | `"4f2a7b8e91c28d9a..."` | **核心安全加签防伪串**。由 Native JNI 或逆向双重加盐 MD5 算法生成：Stage 1 拼接字典序参数与 `loginCode` 算 MD5，Stage 2 拼接全局加盐常量 `sign-kailu-...` 算最终 MD5。 |
| **`xfModel`** | String | 是 | `"0"` | 消费模式代码（默认 `"0"`）。 |
| **`couponId`** | String | 否 | `null` | 优惠券 ID（若使用优惠券抵扣洗浴费用时传入）。 |

#### 请求示例数据包
```http
POST /order/downRate/bluetooth/rateOrder HTTP/1.1
Host: v3-api.china-qzxy.cn
Content-Type: application/x-www-form-urlencoded; charset=utf-8

deviceId=19931&macAddress=C47F0E3E59C5&macType=0001&bigTypeId=1&smallTypeId=1&protocolType=10&randomNumber=e1000000&signature=4f2a7b8e91c28d9a441e8880f0a2d3b4&xfModel=0&loginCode=c4ca4238...&telephone=13800000000&projectId=201
```

#### 响应示例数据包
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 0,
  "message": "success",
  "data": {
    "orderNo": "202611232250330001",
    "downData": "2fdc37d3acb71eb95c1b788bf09823158f9ac6fa77fa09ca4506fa3850046462ff264138c9dd40f0dee173b6579c5aee",
    "perMoney": "5.00",
    "realMoney": "50.00",
    "consumeDate": "2026-11-23 22:50:33",
    "autoDisConTime": 1800,
    "accountId": "1941",
    "minMoney": "0.10"
  }
}
```

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`orderNo`** | String | `"202611232250330001"` | **本次洗浴订单号**。用于手机本地状态跟踪以及后续账单结算。 |
| **`downData`** | String | `"2fdc37d3ac...5aee"` | **48 字节硬件开阀加密载荷**。云端用设备预共享密钥 AES 加密后的控制指令，手机将其装入 BLE `0x21` 帧下发给水表开启阀门。 |
| **`perMoney`** | String | `"5.00"` | 预扣/押金金额（单位：元）。开阀时临时从钱包冻结的最高用水额度。 |
| **`realMoney`** | String | `"50.00"` | 用户当前账户真实可用余额（单位：元）。 |
| **`consumeDate`** | String | `"2026-11-23 22:50:33"`| 订单创建时间戳，格式 `YYYY-MM-DD HH:mm:ss`。 |
| **`autoDisConTime`**| Long | `1800` | 水表无水或无操作自动关闭阀门的保护倒计时（单位：秒，通常 30 分钟）。 |
| **`accountId`** | String | `"1941"` | 扣费学生账户 ID。 |
| **`minMoney`** | String | `"0.10"` | 本次洗浴最低消费起步门槛（单位：元）。 |

---

### 2.2 蓝牙脱机水表消费数据上传结算接口 (`POST /order/upload/bluetooth/data`)
*   **接口地址**：`POST /order/upload/bluetooth/data`
*   **备用端点**：`POST https://wfwoff.china-qzxy.cn/offlineOrder/upload/bluetooth/data`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：手机向水表发送关阀 (`0x22`) 并读取到未上传账单数据 (`0x85`) 后，将水表返回的原始消费脉冲及校验载荷 `xfData` 上传至云端执行最终精准扣款。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`protocolType`**| String| 是 | `"10"` | 蓝牙计费协议版本（从水表 0x23 响应中提取，固定为 `"10"`）。 |
| **`randomNumber`**| String| 是 | `"e1000000"` / `"00000000"` | **水表动态随机数或结账序列字**。官方 App 源码（`BathingBleActivity.chaxueNewshebeiOnback` $\rightarrow$ `BaseDeviceActivity.R5`）通过 `DigitalTrans.bytesToHexString(this.z1)` 传入从水表 0x23 响应中读取的 4 字节动态随机数 Nonce（如 `"e1000000"` 或 `"f7000000"`）。若在未重新查询水表或极端断连回退状态下，变量默认填入 `"00000000"`。服务端在结算接口主要核验 `xfData` 与 `signature`，并向下兼容 `"00000000"`。 |
| **`xfData`** | String | 是 | `"80261123225041000000c900000795..."` | **水表上报的原始 42 字节消费记录载荷 Hex 串**。包含出水时间戳、累计脉冲数（出水量）、水表流水号等，严禁篡改。 |
| **`signature`** | String | 是 | `"8c3b2e9a0f..."` | 上传签名：对字典序参数 `loginCode + telephone + xfData` 加盐计算出的 MD5 校验摘要。 |

#### 请求示例数据包
```http
POST /order/upload/bluetooth/data HTTP/1.1
Host: v3-api.china-qzxy.cn
Content-Type: application/x-www-form-urlencoded; charset=utf-8

protocolType=10&randomNumber=e1000000&xfData=80261123225041000000c90000079500004ddb0200000002000013880000003200000032c47f0e3e59c5&signature=8c3b2e9a0f123456789abcdef0123456&loginCode=c4ca4238...&telephone=13800000000
```

#### 响应示例数据包
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 0,
  "message": "结算成功",
  "data": {
    "orderNo": "202611232250330001",
    "consumeMoney": "0.32",
    "balance": "49.68"
  }
}
```

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`orderNo`** | String | `"202611232250330001"` | 完成结算的洗浴订单号。 |
| **`consumeMoney`**| String | `"0.32"` | **本次洗浴实际扣费金额**（单位：元）。 |
| **`balance`** | String | `"49.68"` | 扣除本次用水费用后，钱包最新的可用余额（单位：元）。 |

---

### 2.3 蓝牙脱机水表开机失败上报接口 (`POST /order/upload/bluetooth/fail`)
*   **接口地址**：`POST /order/upload/bluetooth/fail`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：当云端已签发开阀订单但手机向水表写入指令失败（如蓝牙断连、校验失败）时，通知云端解冻预扣款。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`orderNo`** | String | 是 | `"202611232250330001"` | 需要撤回或标记失败的洗浴订单号。 |
| **`reason`** | String | 否 | `"BLE_WRITE_FAILED"` | 失败原因描述字符串（如超时、断连、校验和不符等）。 |

---

### 2.4 浴室订单结束状态同步 (`POST /order/bathroom/order/closed`)
*   **接口地址**：`POST /order/bathroom/order/closed`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：当完成关阀和记录入库后，主动触发服务端更新该订单为已完结（Closed）状态。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`orderNo`** | String | 是 | `"202611232250330001"` | 已经完成所有结算与水表清除动作的订单号。 |

---

### 3. 远程网络开机结果轮询 (`POST /order/tcpDevice/query/downRateResult`)
*   **接口地址**：`POST /order/tcpDevice/query/downRateResult`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：下发远程开阀指令后，每秒轮询一次该接口，直到水表端向云端回执 ACK。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`xfModel`** | String | 是 | `"0"` | 消费模式代码（默认 `"0"`）。 |
| **`snCode`** | String | 是 | `"C47F0ED9E84A"` | 水表 SN 码。 |
| **`deviceSncode`**| String | 否 | `"C47F0ED9E84A"` | 设备 SN 码。 |

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`orderNo`** | String | `"202611231930450123"` | 开机确认成功后，云端正式生成的洗浴订单流水号。 |
| **`state`** | Int / String | `1` | 状态指示码：`1` 表示设备已确认开机出水。 |
| **`status`** | String | `"SUCCESS"` | 执行结果字符串状态。 |

---

### 4. 远程网络关阀下单 (`POST /order/tcpDevice/closeOrder`)
*   **接口地址**：`POST /order/tcpDevice/closeOrder`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：远程模式下通知云端向水表下发 TCP 关阀指令。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`snCode`** | String | 是 | `"C47F0ED9E84A"` | 水表 SN 码。 |
| **`orderNo`** | String | 是 | `"202611231930450123"` | 开机时获得的当前正在进行中的订单号。 |

---

### 5. 远程网络关阀执行结果轮询 (`POST /order/tcpDevice/closeOrder/result/query`)
*   **接口地址**：`POST /order/tcpDevice/closeOrder/result/query`
*   **功能说明**：轮询关阀指令执行状态。

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`orderNo`** | String | `"202611231930450123"` | 对应的洗浴订单号。 |
| **`state`** | Int / String | `2` | 状态码：`2` 代表水表已彻底执行关阀动作停水。 |

---

### 6. 最终消费金额与账单查询 (`POST /order/consumeOrder/result/query`)
*   **接口地址**：`POST /order/consumeOrder/result/query`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：无论是网络模式还是脱机模式结算后，调用该接口查询本次订单的完整出水时长、脉冲用水量以及扣款金额明细。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`orderNo`** | String | 是 | `"202611231930450123"` | 需要查询最终账单明细的订单流水号。 |

#### 响应示例数据包
```http
HTTP/1.1 200 OK
Content-Type: application/json;charset=UTF-8

{
  "errorCode": 0,
  "message": "查询成功",
  "data": {
    "orderNo": "202611231930450123",
    "preDeductMoney": 5000,
    "consumeMoney": 280,
    "consumeMoneyReal": 280,
    "consumeMoneyGiven": 0,
    "dealMoney": 0,
    "consumeTime": 125,
    "consumeWater": 18
  }
}
```

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`orderNo`** | String | `"202611231930450123"` | 洗浴订单号。 |
| **`preDeductMoney`**| Long | `5000` | 开阀时预扣冻结金额。单位：0.001 元（厘），`5000` 即 5.000 元。 |
| **`consumeMoney`** | Long | `280` | **总消费扣除金额**。单位：0.001 元，`280` 即 0.280 元。 |
| **`consumeMoneyReal`**| Long | `280` | 从本金余额中实际扣除的金额。单位：0.001 元。 |
| **`consumeMoneyGiven`**| Long | `0` | 从活动赠送金余额中扣除的金额。单位：0.001 元。 |
| **`dealMoney`** | Long | `0` | 优惠券抵扣金额或减免金额。单位：0.001 元。 |
| **`consumeTime`** | Long | `125` | **实际用水持续时长**（单位：秒）。 |
| **`consumeWater`** | Long | `18` | **实际消耗水表脉冲点数**（流量单位）。 |

---

## 五、 钱包与充值支付接口 (WalletApi)

### 1. 钱包余额获取 (`GET /account/wallet`)
*   **接口地址**：`GET /account/wallet`
*   **功能说明**：拉取用户当前所有维度的钱包资金余额。

#### 响应 JSON 字段解释表 (`data` 节点)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`money`** | String | `"15.50"` | 钱包本金账户实时可用金额（字符串，单位：元）。 |
| **`balance`** | String | `"15.50"` | 账户实际可用结算余额（单位：元）。 |
| **`walletMoney`** | String | `"15.50"` | 电子钱包现金余额（单位：元）。 |
| **`allMoney`** | String | `"17.50"` | **总资金汇总**（本金 `15.50` + 赠送金 `2.00` = `17.50` 元）。 |

---

### 2. 充值套餐面额列表 (`GET /settlement/recharge/package/list`)
*   **接口地址**：`GET /settlement/recharge/package/list`
*   **功能说明**：拉取当前校区支持的充值梯度面额及满赠优惠规则。

#### 响应 JSON 字段解释表 (`data` 数组项)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`typeId`** | String | `"101"` | 充值套餐档位唯一 ID。 |
| **`amount`** | String | `"10"` | 本档充值应付本金金额（单位：元）。 |
| **`givenMoney`** | String | `"0"` | 该充值档位赠送的赠送金金额（单位：元，如充 50 送 5 则为 `"5"`）。 |

---

### 3. 支付宝充值下单 (`POST /settlement/alipay/recharge/balance`)
*   **接口地址**：`POST /settlement/alipay/recharge/balance`
*   **请求类型**：`application/x-www-form-urlencoded`
*   **功能说明**：提交充值金额与签名，云端调用支付宝统一下单，返回客户端支付宝 SDK 直接调起支付所需的加签字符串。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`payWay`** | String | 是 | `"2"` | 支付渠道代码：`"2"` 代表支付宝移动快捷支付；`"5"` 代表微信支付。 |
| **`rechargeMoney`**| String | 是 | `"10"` | 充值金额数字（整数或保留两位小数，单位：元）。 |
| **`signature`** | String | 是 | `"9a8b7c6d5e4f..."`| 请求加签校验字符串。 |
| **`telPhone`** | String | 是 | `"13800138000"` | 充值目标用户手机号。 |
| **`loginCode`** | String | 是 | `"c4ca4238..."` | 当前用户登录凭据。 |
| **`projectId`** | String | 是 | `"10086"` | 校园校区 ID。 |

#### 响应 JSON 字段解释表
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`data`** | String | `"alipay_sdk=...&sign=..."` | **支付宝官方 SDK 调起支付原始串**。Android 端直接传给 `PayTask.payV2(orderInfo, true)` 即可调起支付宝收银台。 |

---

### 4. 微信充值下单 (`POST /settlement/weChat/recharge/app/balance`)
*   **接口地址**：`POST /settlement/weChat/recharge/app/balance`
*   **说明**：入参除 `payWay="5"` 外与支付宝相同，返回微信开放平台 SDK 所需的调起 JSON 参数（包含 `appid`, `partnerid`, `prepayid`, `package`, `noncestr`, `timestamp`, `sign`）。

---

## 六、 账单流水接口 (BillApi)

### 1. 账户月度流水列表 (`GET /order/query/account/bill/list`)
*   **接口地址**：`GET /order/query/account/bill/list`
*   **功能说明**：按月度分页或全量查询历史洗浴消费明细或账户充值明细。

#### 请求参数字段解释表
| 字段名 | 类型 | 必填 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :---: | :--- | :--- |
| **`month`** | String | 是 | `"2026-11"` | 查询的目标月份，格式为 `YYYY-MM`。 |
| **`billRequestType`**| String| 是 | `"0"` | 账单流水分组类型：`"0"` 代表用水消费扣费记录；`"1"` 代表在线充值加款记录。 |
| **`loginCode`** | String | 是 | `"c4ca4238..."` | 用户登录会话。 |
| **`projectId`** | String | 是 | `"10086"` | 高校校区编号。 |

#### 响应 JSON 字段解释表 (`data` 数组项)
| 字段名 | 类型 | 示例值 | 字段含义与技术解析 |
| :--- | :---: | :--- | :--- |
| **`orderId`** | String | `"202611231930450123"` | 账单关联的历史业务订单号。 |
| **`dealDate`** | String | `"2026-11-23 19:32:50"`| 账单最终在系统入账、结清的时间。 |
| **`consumeDate`** | String | `"2026-11-23 19:30:45"`| 学生实际在水表开启水阀开始出水的时间。 |
| **`typeName`** | String | `"热水淋浴消费"` | 账单业务分类展示名（如“热水淋浴消费”、“吹风机消费”等）。 |
| **`consumeMoney`**| String | `"0.28"` | 本次记录合计扣费金额（单位：元）。 |
| **`consumeMoneyReal`**| String| `"0.28"` | 从本金账户实际扣款金额（单位：元）。 |
| **`consumeMoneyGiven`**| String| `"0.00"` | 从赠送金账户抵扣金额（单位：元）。 |
| **`preDeductMoney`**| String | `"5.00"` | 初始开阀预冻结金额（单位：元）。 |
| **`upState`** | String | `"1"` | 状态标记：`"1"` 表示账单已结算入账。 |
