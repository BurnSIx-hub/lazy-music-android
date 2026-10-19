plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.lazymusic.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lazymusic.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 26
        versionName = "1.12.0"

        // Только arm64: youtubedl-android тащит свой Python на каждую ABI,
        // а проверяем на Galaxy S23. Остальные ABI — когда понадобятся.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Сжатие выключено намеренно: основной вес APK — Python внутри
            // youtubedl-android, его R8 не тронет, а ломать отражение рискованно
            isMinifyEnabled = false
            // Подпись отладочным ключом. Он стандартный и не секрет, зато APK
            // ставится сразу. Для настоящего выпуска нужен свой ключ — тогда
            // придётся удалить и поставить заново: подписи разные
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Питон и yt-dlp распаковываются в filesDir при первом запуске,
        // сжимать их в APK смысла нет.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.youtubedl.library)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.compose.material.icons)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
