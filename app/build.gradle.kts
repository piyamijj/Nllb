import java.net.URL


plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.piyamijj.tercuman.nllb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.piyamijj.tercuman.nllb"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "VARIANT", "\"NLLB\"")
        buildConfigField("String", "MODEL_LABEL", "\"NLLB-200 distilled 600M (int8)\"")
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
                ?: project.rootProject.file("keystore/tercuman-release.jks").absolutePath
            if (file(path).exists()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "tercuman123"
                keyAlias = System.getenv("KEY_ALIAS") ?: "tercuman"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "tercuman123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val path = System.getenv("KEYSTORE_PATH")
                ?: project.rootProject.file("keystore/tercuman-release.jks").absolutePath
            signingConfig = if (file(path).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

    androidResources {
        // Whisper/VAD modelleri APK icinde sikistirilmadan dursun.
        noCompress += listOf("onnx")
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES"
        )
        // sherpa-onnx AAR'ı ile onnxruntime-android aynı native
        // libonnxruntime.so dosyasını taşır (aynı 1.28.x serisi).
        jniLibs.pickFirsts += "**/libonnxruntime.so"
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // Çeviri motoru (sherpa'nın gömülü ORT'si ile aynı 1.28.x serisi).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// sherpa-onnx'in normal AAR'ı kendi libonnxruntime.so'sunu getirir ve onnxruntime-android
// ile sembol sürümü çakışır (OrtGetApiBase@VERS_1.28.2 / 1.28.0). ORT'si statik gömülü
// sürümüyle değiştiriyoruz; böylece tek libonnxruntime.so (onnxruntime-android) kalır.
val useStaticSherpa = tasks.register("useStaticSherpa") {
    val libsDir = file("libs")
    doLast {
        libsDir.listFiles { f -> f.name.startsWith("sherpa-onnx-") && f.name.endsWith(".aar") }
            ?.forEach { aar ->
                val ver = aar.name.removePrefix("sherpa-onnx-").removeSuffix(".aar")
                val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$ver/" +
                    "sherpa-onnx-static-link-onnxruntime-$ver.aar"
                val tmp = File(aar.parentFile, aar.name + ".tmp")
                URL(url).openStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
                aar.delete(); tmp.renameTo(aar)
                println("useStaticSherpa: $ver statik ORT sürümüyle değiştirildi (${aar.length()} bayt)")
            }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(useStaticSherpa) }
