package com.qzxy.lite.ble

/**
 * 纯 Kotlin BLE 行帧缓冲器（线程安全需由调用方保证）。
 *
 * 解决直接基于单个 `indexOf('\n')` 拆分时存在的三个隐患：
 * 1. 同一次 Notify 粘连携带多条帧（`#...16\n#...16\n`）时一次性全部拆出；
 * 2. 自动丢弃未以 `#` 开头的前置噪声，避免坏数据污染下一帧；
 * 3. 设置最大缓冲上限 [maxBufferBytes]，防止异常链路下无换行噪声导致内存无限增长。
 */
internal class BleLineFramingBuffer(
    private val maxBufferBytes: Int = DEFAULT_MAX_BUFFER_BYTES,
) {
    private val buffer = StringBuilder()

    fun append(bytes: ByteArray): List<String> {
        if (bytes.isEmpty()) return emptyList()
        buffer.append(String(bytes, Charsets.US_ASCII))
        if (buffer.length > maxBufferBytes) {
            // 超限时仅保留尾部可能尚未闭合的一帧（从最后一个 '#' 起）
            val lastStart = buffer.lastIndexOf(FRAME_START)
            if (lastStart >= 0) {
                buffer.delete(0, lastStart)
            } else {
                buffer.clear()
            }
            if (buffer.length > maxBufferBytes) {
                buffer.delete(0, buffer.length - maxBufferBytes)
            }
        }

        val emitted = mutableListOf<String>()
        while (true) {
            val startIdx = buffer.indexOf(FRAME_START)
            if (startIdx > 0) {
                buffer.delete(0, startIdx)
            } else if (startIdx < 0) {
                buffer.clear()
                break
            }
            val newlineIdx = buffer.indexOf(FRAME_END)
            if (newlineIdx < 0) break
            emitted += buffer.substring(0, newlineIdx + 1)
            buffer.delete(0, newlineIdx + 1)
        }
        return emitted
    }

    fun clear() {
        buffer.clear()
    }

    companion object {
        private const val FRAME_START = "#"
        private const val FRAME_END = "\n"
        const val DEFAULT_MAX_BUFFER_BYTES = 4096
    }
}
