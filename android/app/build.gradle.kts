plugins {
    id("com.android.application")
}

android {
    namespace = "com.amadeus.mobile"
    compileSdk = 35

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
            jniLibs.directories += rootProject.file("../payload/build/jniLibs").path
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
    testImplementation("junit:junit:4.13.2")
    // android.jar 里的 org.json 在 JVM 单元测试中只是抛异常的桩
    testImplementation("org.json:json:20260814")
}
