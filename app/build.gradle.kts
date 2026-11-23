import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名：keys/keystore.properties（已 gitignore）；没有就回落到 debug 签名
val keyProps = Properties().apply {
    val f = rootProject.file("keys/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val storeFilePath: String? = keyProps.getProperty("BUXINGTU_STORE_FILE")

android {
    namespace = "com.haowu.buxingtu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.haowu.buxingtu"
        minSdk = 26
        targetSdk = 35
        versionCode = (providers.gradleProperty("versionCode").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("versionName").orNull ?: "0.1.0"
        // 自动更新用的 Cloudreve 分享 ID，Flow 流水线构建时传 -PupdateShare；本地构建为空，App 不检查更新
        buildConfigField("String", "UPDATE_SHARE", "\"${providers.gradleProperty("updateShare").orNull ?: ""}\"")
        // ML Kit 人脸检测带原生库，只打主流手机的两种 ABI，控制安装包大小
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = keyProps.getProperty("BUXINGTU_STORE_PASSWORD")
                keyAlias = keyProps.getProperty("BUXINGTU_KEY_ALIAS")
                keyPassword = keyProps.getProperty("BUXINGTU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (storeFilePath != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    val camerax = "1.4.1"
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // 瘦脸大眼用的人脸轮廓：内置模型版本，不依赖 Google Play 服务
    implementation("com.google.mlkit:face-detection:16.1.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    // 界面截图测试（Robolectric 里渲染 Compose，输出到 build/ui-previews/）
    testImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
