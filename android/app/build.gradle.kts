plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.woorizip.radio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.woorizip.radio"
        minSdk = 26
        targetSdk = 35
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("appVersion") as String?) ?: "dev"
    }

    // 가족용 고정 서명 키: GitHub Secrets(ANDROID_KEYSTORE_B64, ANDROID_KEYSTORE_PASSWORD)에서 빌드 때 받아 씀.
    // 빌드할 때마다 키가 바뀌면 "앱을 설치할 수 없음"(서명 불일치)으로 덮어 설치가 안 되기 때문.
    val ksFile = System.getenv("ANDROID_KEYSTORE_FILE")?.let { file(it) }
    val ksPass = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val useFamilyKey = ksFile != null && ksFile.exists() && !ksPass.isNullOrBlank()
    if (useFamilyKey) {
        signingConfigs.create("family") {
            storeFile = ksFile
            storeType = "PKCS12"
            storePassword = ksPass
            keyAlias = "woorizip"
            keyPassword = ksPass
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (useFamilyKey) "family" else "debug")
        }
        debug {
            if (useFamilyKey) signingConfig = signingConfigs.getByName("family")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
