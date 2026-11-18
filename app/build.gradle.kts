plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bumki.frontal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bumki.frontal"
        minSdk = 26
        // 34로 두면 안드로이드 15에서도 화면이 상태바·내비게이션바 아래로 들어가지 않습니다.
        targetSdk = 34
        // GitHub 빌드 번호로 버전을 자동으로 올립니다(앱 안 업데이트 확인에 사용).
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "0.2.$run"
    }

    // 빌드할 때마다 같은 키로 서명해야 앱을 지우지 않고 업데이트할 수 있습니다(기록 보존).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
