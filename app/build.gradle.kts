import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

// sherpa-onnx AAR'ındaki libonnxruntime.so, Java JNI'nin (onnxruntime4j) ihtiyaç
// duyduğu OrtGetApiBase sembolünü vermiyor ("UnsatisfiedLinkError"). Bu kopyayı
// AAR'dan çıkarıp onnxruntime-android'in kendi kütüphanesini kullanıyoruz.
val stripSherpaOrt = tasks.register("stripSherpaOrt") {
    val libsDir = file("libs")
    doLast {
        libsDir.listFiles { f -> f.name.startsWith("sherpa-onnx-") && f.name.endsWith(".aar") }
            ?.forEach { aar ->
                val tmp = File(aar.parentFile, aar.name + ".tmp")
                var removed = 0
                ZipFile(aar).use { zf ->
                    ZipOutputStream(tmp.outputStream().buffered()).use { zo ->
                        for (e in zf.entries()) {
                            if (e.name.startsWith("jni/") && e.name.endsWith("/libonnxruntime.so")) {
                                removed++
                                continue
                            }
                            zo.putNextEntry(ZipEntry(e.name))
                            zf.getInputStream(e).use { it.copyTo(zo) }
                            zo.closeEntry()
                        }
                    }
                }
                if (removed > 0) {
                    aar.delete(); tmp.renameTo(aar)
                } else tmp.delete()
                println("stripSherpaOrt: ${aar.name} -> $removed kopya çıkarıldı")
            }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(stripSherpaOrt) }

// TANI: sembol incelemesi (geçici)
tasks.register("diagOrt") {
    doLast {
        val script = """
            set -x
            d=${'$'}(mktemp -d); cd ${'$'}d
            curl -sL -o s.aar https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar
            curl -sL -o o.aar https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.28.0/onnxruntime-android-1.28.0.aar
            unzip -qo s.aar 'jni/arm64-v8a/*' -d s; unzip -qo o.aar 'jni/arm64-v8a/*' -d o
            for f in s/jni/arm64-v8a/*.so o/jni/arm64-v8a/*.so; do
              echo "== ${'$'}f"; readelf -d ${'$'}f | grep -E "SONAME|NEEDED"
              echo "defined:"; nm -D --defined-only ${'$'}f | grep -c Ort; nm -D --defined-only ${'$'}f | grep -E "OrtGetApiBase|OrtApi" | head
              echo "undefined:"; nm -D -u ${'$'}f | grep -E "OrtGetApiBase" | head -2
            done
        """.trimIndent()
        val p = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().forEachLine { println("DIAG " + it) }
        p.waitFor()
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("diagOrt") }
