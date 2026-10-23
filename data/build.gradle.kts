// Room dan implementasi repositori. Skema versi 1 ada di db/ dan dirancang di docs/model-data.md.
// Plugin serialization dipakai untuk DTO JSON Retrofit di remote/ (Tahap 9, server-sync.md).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.roziqrizal.rizqflow.data"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Robolectric butuh sumber daya Android agar Room bisa berjalan di JVM.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    // Berkas skema JSON dibaca langsung (context.assets) oleh tes migrasi untuk membangun versi lama.
    sourceSets {
        getByName("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

// Berkas skema JSON masuk git supaya setiap versi skema bisa diuji migrasinya.
room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))
    implementation(libs.androidx.room.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.tink.android)
    ksp(libs.androidx.room.compiler)

    // Klien HTTP untuk akun server dan sync (Tahap 9, server-sync.md) — lihat data/remote/. JSON
    // di-encode/decode manual lewat kotlinx.serialization (bukan converter Retrofit pihak ketiga),
    // Retrofit cuma mengantar RequestBody/ResponseBody mentah (didukung bawaan, tanpa converter).
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Tes DAO dan repositori berjalan di JVM lewat Robolectric (JUnit 4), tanpa emulator, jadi ikut CI.
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.core)
    // Tes RizqflowApiService terhadap server HTTP sungguhan (bukan mock interface) di JVM, tanpa Robolectric.
    testImplementation(libs.okhttp.mockwebserver)
}
