plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.qzxy.lite"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.qzxy.lite"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.7"
    }

    //signingConfigs {
    //    create("release") {
    //        storeFile = file("F:/project my/趣智校园/out/mod.keystore")
    //        storePassword = "android"
    //        keyAlias = "mod"
    //        keyPassword = "android"
    //    }
    //}
    // 移除硬编码签名配置，构建纯无签名 APK 供用户自行签名
    buildTypes {
        release {
            // 启用 R8 代码树摇与资源缩减（proguard-rules.pro 配合 -dontobfuscate 保持反射/SDK 稳定）
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            //signingConfig = signingConfigs.getByName("release")
            signingConfig = null
            buildConfigField("boolean", "DIAG_LOG", "false")
        }
        debug {
            isMinifyEnabled = false
            buildConfigField("boolean", "DIAG_LOG", "false")
        }
        // 诊断版
        create("diag") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = null
            matchingFallbacks += listOf("release")
            versionNameSuffix = "-diag"
            buildConfigField("boolean", "DIAG_LOG", "true")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 支付宝官方 SDK（充值拉起）
    implementation("com.alipay.sdk:alipaysdk-android:15.8.11")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)

    // 网络层：OkHttp + Retrofit + kotlinx-serialization
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.converter)
    implementation(libs.kotlinx.serialization.json)

    // 会话存储：DataStore + EncryptedSharedPreferences
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.android)

    // 扫描饮水机二维码：CameraX 出帧 + ZXing 纯 Java 解码（不依赖 Google Play 服务）
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
