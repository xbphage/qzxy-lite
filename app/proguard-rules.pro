# 仅执行代码/图标树摇与资源缩减，保留类名与方法名以确保反射/支付宝 SDK 及堆栈排障稳定
-dontobfuscate

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.qzxy.lite.**$$serializer { *; }
-keepclassmembers class com.qzxy.lite.** { *** Companion; }
-keepclasseswithmembers class com.qzxy.lite.** { kotlinx.serialization.KSerializer serializer(...); }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepattributes Signature, Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Alipay SDK
-keep class com.alipay.** { *; }
-dontwarn com.alipay.**
-keep class com.ta.utdid2.** { *; }
-dontwarn com.ta.utdid2.**
-keep class com.ut.device.** { *; }
-dontwarn com.ut.device.**

# AndroidX Security Crypto / Tink
-dontwarn com.google.crypto.tink.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
