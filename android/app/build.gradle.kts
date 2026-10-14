plugins {
    id("com.android.application")
}

android {
    namespace = "com.amadeus.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.amadeus.mobile"
        minSdk = 28
        // node 与它的 .so 以 jniLibs 形式解压到 nativeLibraryDir 执行，因此不受 targetSdk ≥ 29 禁止执行私有目录文件的限制
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    sourceSets {
        getByName("main") {
            assets.directories += rootProject.file("../payload/build/assets").path
            // 前端、识别模型与 emote 素材，由 scripts/fetch-deps.mjs 与 web/build.mjs 生成
            assets.directories += rootProject.file("../build/assets").path
            jniLibs.directories += rootProject.file("../payload/build/jniLibs").path
            // 应用图标取自 amadeus-next 的 logo，由 scripts/fetch-deps.mjs 生成
            res.directories += rootProject.file("../build/res").path
        }
    }

    packaging {
        jniLibs {
            // 必须解压成真实文件：node 要被 exec，不能留在 APK 里按页映射
            useLegacyPackaging = true
        }
    }

    androidResources {
        // payload.zip 自身已压缩，再压一次只会拖慢构建
        noCompress += "zip"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.webkit:webkit:1.17.1")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.camera:camera-core:1.6.2")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-video:1.6.2")
    // 端侧流式识别；AAR 由 scripts/fetch-deps.mjs 按锁定的 sha256 下载
    implementation(files("libs/sherpa-onnx.aar"))
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 在 JVM 单元测试中只是抛异常的桩
    testImplementation("org.json:json:20260814")
}
