import java.util.Properties
import java.net.URI
import java.net.URISyntaxException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------------------------------------------
// Alamat API (Tahap 12, "alamat API di aplikasi bisa dikonfigurasi supaya pindah ke VPS khusus mudah").
//
// BuildConfig.API_BASE_URL dipilih dengan prioritas berikut (yang pertama ada dan tidak kosong menang):
//   1. -PrizqflowApiBaseUrl=<alamat>                 (baris perintah Gradle)
//   2. rizqflow.apiBaseUrl=<alamat> di local.properties   (tidak di-commit; aman untuk alamat LAN pribadi)
//   3. variabel lingkungan RIZQFLOW_API_BASE_URL     (CI)
//   4. bawaan per build type: debug http://10.0.2.2:8000/api/ (emulator), release https://rizqflow-api.roziqrizal.com/api/
// Nilai kosong atau hanya spasi dianggap tidak diisi. Pindah VPS cukup mengganti alamat ini; tidak ada kode yang berubah.
//
// Pemeriksaan saat konfigurasi Gradle (pesan galat menyebut sumbernya): harus http(s) dengan host, tanpa nama
// pengguna/sandi, tanpa spasi atau tanda kutip, dan WAJIB berakhir dengan "/" (Retrofit menolak tanpa itu).
// Build RELEASE (tugas apa pun yang namanya memuat "Release" di :app) tambahan wajib https://; bila tidak, build gagal.
// Pemeriksaan ini sengaja hanya berlaku bila rilis benar-benar dibangun, supaya alamat http untuk HP fisik
// (-PrizqflowApiBaseUrl=http://192.168.x.x:8000/api/) tidak menggagalkan build debug. Aturannya dicerminkan di
// aplikasi oleh data/.../remote/ApiBaseUrl.kt (skrip build tidak bisa memanggil :data); ubah keduanya bersama.
//
// MEMBANGUN APK DEBUG UNTUK HP FISIK (HP tidak punya alias 10.0.2.2, jadi bawaan debug tidak bisa dipakai):
//   a. LAN: server jalan di PC dengan `php artisan serve --host=0.0.0.0 --port=8000` (dari server/), PC dan HP satu
//      Wi-Fi, lalu
//        ./gradlew :app:assembleDebug -PrizqflowApiBaseUrl=http://192.168.x.x:8000/api/
//      (ganti 192.168.x.x dengan alamat IP PC; firewall PC harus mengizinkan port 8000). Atau tulis sekali di
//      local.properties: rizqflow.apiBaseUrl=http://192.168.x.x:8000/api/
//   b. adb reverse (kabel USB, tanpa tahu IP PC): jalankan `adb reverse tcp:8000 tcp:8000`, lalu
//        ./gradlew :app:assembleDebug -PrizqflowApiBaseUrl=http://localhost:8000/api/
//      (perintah adb reverse harus diulang tiap HP dicabut-pasang atau adb dimulai ulang).
//   c. Server publik: -PrizqflowApiBaseUrl=https://rizqflow-api.roziqrizal.com/api/ (https, tanpa http sama sekali).
// Build debug boleh memakai http karena app/src/debug punya network security config yang mengizinkan cleartext;
// build release tidak punya izin itu dan menolak alamat http (Gradle di sini, ApiBaseUrl.validate di aplikasi).
// ---------------------------------------------------------------------------------------------------------------
fun blankToNull(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

val localApiBaseUrl: String? = blankToNull(
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull
        ?.let { text -> Properties().apply { load(text.reader()) }.getProperty("rizqflow.apiBaseUrl") },
)

val apiBaseUrlOverride: Pair<String, String>? = listOf(
    "-PrizqflowApiBaseUrl" to blankToNull(providers.gradleProperty("rizqflowApiBaseUrl").orNull),
    "rizqflow.apiBaseUrl di local.properties" to localApiBaseUrl,
    "variabel lingkungan RIZQFLOW_API_BASE_URL" to blankToNull(providers.environmentVariable("RIZQFLOW_API_BASE_URL").orNull),
).firstNotNullOfOrNull { (source, value) -> value?.let { source to it } }

/** Memeriksa [value] dari [source]; mengembalikan nilai yang siap ditulis ke BuildConfig atau menggagalkan build. */
fun checkApiBaseUrl(value: String, source: String): String {
    fun fail(reason: String): Nothing =
        throw GradleException("Alamat API dari $source tidak sah: $reason. Nilainya: \"$value\". Contoh yang benar: https://rizqflow-api.roziqrizal.com/api/")
    if (value.any { it.isWhitespace() || it == '"' || it == '\\' }) fail("tidak boleh mengandung spasi, tanda kutip, atau garis miring terbalik")
    val uri = try {
        URI(value)
    } catch (_: URISyntaxException) {
        fail("bukan URL yang bisa dibaca")
    }
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") fail("harus diawali http:// atau https://")
    if (uri.host.isNullOrBlank()) fail("nama host kosong")
    if (uri.userInfo != null) fail("tidak boleh memuat nama pengguna atau sandi")
    if (!value.endsWith("/")) fail("harus berakhir dengan garis miring (/), Retrofit menolak tanpa itu")
    return value
}

val debugApiBaseUrl: String = apiBaseUrlOverride?.let { (source, value) -> checkApiBaseUrl(value, source) } ?: "http://10.0.2.2:8000/api/"
val releaseApiBaseUrl: String = apiBaseUrlOverride?.let { (source, value) -> checkApiBaseUrl(value, source) } ?: "https://rizqflow-api.roziqrizal.com/api/"

// Build RELEASE wajib https://. Diperiksa saat grafik tugas siap dan hanya bila ada tugas rilis di :app yang akan jalan.
gradle.taskGraph.whenReady {
    val releaseRequested = allTasks.any { it.path.startsWith(":app:") && it.name.contains("Release", ignoreCase = true) }
    if (releaseRequested && !releaseApiBaseUrl.startsWith("https://", ignoreCase = true)) {
        val source = apiBaseUrlOverride?.first ?: "bawaan build rilis"
        throw GradleException("Build RELEASE wajib memakai alamat API https://, tetapi dari $source didapat: \"$releaseApiBaseUrl\". Pakai alamat https:// atau bangun varian debug.")
    }
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
    // Cara memilih dan memeriksa nilainya ada di komentar besar di atas file ini.
    // rizqflow-api.roziqrizal.com (Tahap 12) BELUM hidup; bawaan release masih placeholder
    // sampai subdomain itu benar-benar ada. 10.0.2.2 (bawaan debug) adalah alias emulator Android ke
    // localhost mesin pengembang (`php artisan serve`, port 8000, dijalankan dari server/).
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"$releaseApiBaseUrl\"")
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

    // Input cerdas on-device (Tahap 11, OCR screenshot mutasi dan e-statement PDF): ML Kit Text Recognition
    // (model bawaan, jalan di perangkat tanpa jaringan) untuk gambar, dan PdfBox-Android untuk membaca teks
    // dari PDF e-statement. Keduanya hanya dipakai di ocr/ (adaptor Android); parsernya ada di :domain.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.pdfbox.android)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5) // menghubungkan kotlin.test ke JUnit 5 di modul Android
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
