plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.embytv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.embytv"
        minSdk = 24
        targetSdk = 35
        versionCode = 286
        versionName = "1.3.66"
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("release.keystore")
            storePassword = "embytv"
            keyAlias = "embytv"
            keyPassword = "embytv"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    packagingOptions {
        // Resolve the shared C++ runtime bundled by MPV and Media3.
        pickFirst("lib/**/libc++_shared.so")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.5")

    // View 体系 + Fragment 导航
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.1")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // SMB 网络播放（jcifs-ng）
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

    // 弹幕渲染（DanmakuFlameMaster）
    implementation("com.github.bilibili:DanmakuFlameMaster:0.9.25")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Emby 海报墙图片加载（View 版 Coil）
    implementation("io.coil-kt:coil:2.6.0")

    // ====== Android TV 支持 ======
    implementation("androidx.leanback:leanback:1.0.0")
    // DpadRecyclerView — TV 焦点优化横向列表（参考 AfuseKtV；Maven Central 最新 1.5.0-beta01）
    implementation("com.rubensousa.dpadrecyclerview:dpadrecyclerview:1.5.0-beta01")

    // ====== 播放内核 ======
    // EXO 内核（Media3 官方 1.10.1）
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-exoplayer-smoothstreaming:1.10.1")
    implementation("androidx.media3:media3-datasource-rtmp:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")
    // Jellyfin 预编译的 Media3 FFmpeg 软解扩展（EAC3/AC3/DTS 等硬解不支持的格式走 FFmpeg 软解）
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.6.1+1")
    // ASS 字幕拓展（libass 渲染）：让 EXO 正确解析/渲染 ASS/SSA 字幕（参考 AfuseKtV "ass拓展" / Jellyfin）
    implementation("io.github.peerless2012:ass-media:0.5.1")

    // MPV 内核（fongmi 修改版，含杜比视界直通支持）
    implementation(files("libs/mpv-android-lib-0.1.12-fongmi.aar"))
}