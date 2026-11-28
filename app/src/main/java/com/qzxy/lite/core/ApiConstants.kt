package com.qzxy.lite.core

/**
 * 服务端点总览（来源 notes/API.md §1，全部 Observed）。
 */
object ApiConstants {
    /** 主业务 API（wfwapi） */
    const val WFW_API = "https://wfwapi.china-qzxy.cn/"

    /** wfwapi 域名（会话独立于 v3-api） */
    const val WFWAPI_HOST = "wfwapi.china-qzxy.cn"

    /** v3 网关：**登录链/用户端点实测在此**（2026-11-13 探测：wfwapi 同路径 404，v3-api 返回业务封装）；
     *  客服/充值/退款 H5 也走此网关，鉴权用 v3LoginCode。 */
    const val V3_API = "https://v3-api.china-qzxy.cn/"

    /** 预付费水电（独立账号体系，凭据不得与 wfwapi 混用） */
    const val PREPAY_USER = "https://prepay.china-qzxy.cn/prepay-user/"

    /** 离线设备订单 */
    const val WFW_OFF = "https://wfwoff.china-qzxy.cn/"

    /** 公共参数 version 取值：与被分析客户端版本对齐 */
    const val APP_VERSION = "6.5.28"

    /** 请求头 Config-Keys 的固定取值（notes/API.md §2 u0() Observed） */
    const val CONFIG_KEYS =
        "module_list,advertise_type,question_list,service_phone_list,banner_list_app,activity_list_app,aliCard_popup_config"
}
