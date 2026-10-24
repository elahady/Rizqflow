plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.roziqrizal.rizqflow"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.roziqrizal.rizqflow"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // API_BASE_URL dikonfigurasi lewat BuildConfig (bukan ditanam di kode remote/), sesuai prinsip
    // "alamat API bisa dikonfigurasi" di CLAUDE.md — pindah VPS tidak butuh mengubah data/remote/.
    // rizqflow-api.roziqrizal.com (Tahap 12) BELUM hidup; nilai release di bawah masih placeholder
    // sampai subdomain itu benar-benar ada.
    buildTypes {
        debug {
            // 10.0.2.2 adalah alias bawaan emulator Android ke localhost mesin pengembang
            // (`php artisan serve`, port default 8000, dijalankan dari server/).
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8000/api/\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"https://rizqflow-api.roziqrizal.com/api/\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // BuildConfig.DEBUG menampilkan tombol Masuk uji hanya di build debug
    }

    testOptions {
        // Tes memakai JUnit Jupiter dan kotlin.test, sama dengan :domain.
        unitTests.all { it.useJUnitPlatform() }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.auth)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Pembelian Pro (Tahap 7): Play Billing 9.1.0 (terbaru di Google Maven 2026-10-24). Memakai artefak
    // `billing` biasa, bukan `billing-ktx`; pembungkus coroutine ada di PlayBillingGateway.
    implementation(libs.play.billing)

    // Undang anggota ruang keluarga lewat QR (Tahap 9/13): pindai dengan ML Kit + CameraX, render
    // dengan qrcode-kotlin (murni Kotlin, tanpa menarik seluruh kodebase ZXing) -- keduanya sudah
    // diriset dan diputuskan di roadmap.md Tahap 13 (2026-10-24), bukan pilihan baru.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.qrcode.kotlin)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5) // menghubungkan kotlin.test ke JUnit 5 di modul Android
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
