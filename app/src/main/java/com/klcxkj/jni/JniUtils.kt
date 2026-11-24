package com.klcxkj.jni

import android.util.Log
import java.security.MessageDigest
import java.util.TreeMap

/**
 * 趣智校园 Native 签名与参数加密工具类。
 * 对应 official libklcxkjencry.so 中的 `Java_com_klcxkj_jni_JniUtils_signParams`。
 * 当 native 库因架构等原因无法加载时，提供基于逆向分析得出的 MD5 加盐降级实现。
 */
object JniUtils {
    private const val TAG = "JniUtils"
    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("klcxkjencry")
            isNativeLoaded = true
            Log.d(TAG, "libklcxkjencry.so successfully loaded")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to load libklcxkjencry.so: ${t.message}, fallback will be used")
            isNativeLoaded = false
        }
    }

    @JvmStatic
    external fun signParams(loginCode: String, params: String): String

    /**
     * 对参数字典进行字典序排序拼接，并使用 JNI 或降级算法生成 signature 字符串。
     * 排序规则：按 key 升序，拼接 "key1value1key2value2..."。
     */
    fun sign(loginCode: String, params: Map<String, String>): String {
        val sorted = TreeMap<String, String>(params)
        val sb = StringBuilder()
        for ((k, v) in sorted) {
            sb.append(k).append(v)
        }
        val rawParamString = sb.toString()
        return safeSign(loginCode, rawParamString)
    }

    fun safeSign(loginCode: String, paramsString: String): String {
        if (isNativeLoaded) {
            try {
                val res = signParams(loginCode, paramsString)
                if (!res.isNullOrBlank()) return res
            } catch (t: Throwable) {
                Log.w(TAG, "Native signParams failed: ${t.message}, using fallback")
            }
        }
        return fallbackSign(paramsString)
    }

    private fun fallbackSign(paramsString: String): String {
        // 从 libklcxkjencry.so 静态分析提取的密钥
        val key = "&key=sign-kailu-855c74a88b8b494187c99b08b8c9a744"
        val raw = paramsString + key
        return try {
            val md = MessageDigest.getInstance("MD5")
            val bytes = md.digest(raw.toByteArray(Charsets.UTF_8))
            bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
