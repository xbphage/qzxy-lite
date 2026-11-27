package com.qzxy.lite.core

import java.security.MessageDigest

private const val HEX_LOWER = "0123456789abcdef"
private const val HEX_UPPER = "0123456789ABCDEF"

/** 计算字符串的小写 32 位 MD5 摘要。 */
fun md5Hex(input: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return bytes.toHexString(uppercase = false)
}

/** 将字节数组格式化为紧凑 Hex 字符串。 */
fun ByteArray.toHexString(uppercase: Boolean = false): String {
    val digits = if (uppercase) HEX_UPPER else HEX_LOWER
    return buildString(size * 2) {
        for (b in this@toHexString) {
            val v = b.toInt() and 0xFF
            append(digits[v ushr 4])
            append(digits[v and 0x0F])
        }
    }
}

/** 将 Hex 字符串（可含空白或冒号分隔符）解码为字节数组。 */
fun String.hexToByteArray(): ByteArray {
    val clean = filterNot { it.isWhitespace() || it == ':' }
    require(clean.length % 2 == 0) { "Hex 字符串长度必须为偶数: ${clean.length}" }
    return ByteArray(clean.length / 2) { i ->
        val hi = Character.digit(clean[i * 2], 16)
        val lo = Character.digit(clean[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "包含非法 Hex 字符" }
        ((hi shl 4) or lo).toByte()
    }
}

/** 将 MAC 字符串格式化为带冒号大写形式（如 `C4:7F:0E:3E:59:C5`）。 */
fun formatColonMac(mac: String): String {
    val clean = mac.replace(":", "").trim().uppercase()
    return if (clean.length == 12) clean.chunked(2).joinToString(":") else mac.trim().uppercase()
}
