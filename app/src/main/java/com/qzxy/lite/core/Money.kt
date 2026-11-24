package com.qzxy.lite.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * 服务端金额字段有两种互不兼容的形态（notes/账单接口.md §1.4、notes/account-context.md）：
 * - JSON 数字（如 `683`、`190`）：单位是 **0.001 元**，683 = ¥0.683、190 = ¥0.19；
 * - JSON 字符串（如 `"0.39"`、`"1.073"`）：单位已经是**元**。
 *
 * 早期实现把整数当「分」除以 100，190 被显示成 ¥1.90（10 倍误差）；
 * 若反过来一律除 1000，字符串字段又会缩小 1000 倍。
 * 这里用 [JsonPrimitive.isString] 严格区分，禁止按字段名猜单位。
 */
fun JsonElement?.asYuanOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    val value = primitive.content.toDoubleOrNull() ?: return null
    return if (primitive.isString) value else value / 1000.0
}

/** 元 → 展示文本：最多 3 位小数，去掉无意义的尾零（0.190 → "0.19"、0.312 → "0.312"）。 */
fun formatYuan(yuan: Double): String {
    val text = String.format(Locale.CHINA, "%.3f", yuan).trimEnd('0').trimEnd('.')
    return if (text.isEmpty() || text == "-0") "0" else text
}

/**
 * 反序列化阶段就把金额归一成"元"字符串：
 * JSON 数字（683 / 190）按 0.001 元换算成 "0.683" / "0.19"；
 * JSON 字符串（"0.39" / "1.073"）已是元，原样保留。
 * 用于 [com.qzxy.lite.data.model.UserAccount] 等原本直接吃原始值的字段。
 */
@OptIn(ExperimentalSerializationApi::class)
object YuanStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("YuanString?", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        if (!decoder.decodeNotNullMark()) return decoder.decodeNull()
        val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
        val element = jsonDecoder.decodeJsonElement()
        val yuan = element.asYuanOrNull()
        return if (yuan != null) formatYuan(yuan) else (element as? JsonPrimitive)?.content
    }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) encoder.encodeNull() else encoder.encodeString(value)
    }
}
