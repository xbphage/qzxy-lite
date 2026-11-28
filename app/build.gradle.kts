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
        versionCode = 6
        versionName = "1.0.6"
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
            // 正式版签名（mod.keystore）；不混淆以保证运行稳定
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "17"
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

    // MQTT 可选模块（设备/订单实时推送）
    implementation(libs.paho.mqtt)

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
