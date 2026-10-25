package com.roziqrizal.rizqflow.ui.ruangkeluarga

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.roziqrizal.rizqflow.R
import com.roziqrizal.rizqflow.domain.sync.RoomInvite
import com.roziqrizal.rizqflow.domain.sync.RoomInviteCorrupt
import com.roziqrizal.rizqflow.ui.theme.spacing
import java.util.concurrent.Executors

/**
 * Pindai undangan QR ruang keluarga (Tahap 9/13). Kamera dan ML Kit berjalan hanya selagi layar ini
 * terbuka; berhenti begitu satu kode QR valid terbaca ([onScanned] dipanggil sekali) atau ditutup.
 * Kode yang bukan undangan Rizqflow atau rusak diabaikan dengan pesan lembut, kamera tetap jalan.
 */
@Composable
fun QrScanScreen(onClose: () -> Unit, onScanned: (RoomInvite) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spacing = MaterialTheme.spacing
    BackHandler(onBack = onClose)

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var invalidCode by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> hasPermission = granted }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        TextButton(onClick = onClose, modifier = Modifier.padding(start = spacing.s3, top = spacing.s2)) { Text(stringResource(R.string.onb_back)) }
        Text(
            stringResource(R.string.family_scan_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(horizontal = spacing.s5).padding(top = spacing.s2),
        )

        if (!hasPermission) {
            Column(modifier = Modifier.padding(spacing.s5)) {
                Text(
                    stringResource(R.string.family_scan_permission_denied),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.padding(top = spacing.s3).fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.family_scan_permission_action)) }
            }
        } else {
            Text(
                stringResource(R.string.family_scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.s5).padding(top = spacing.s1, bottom = spacing.s3),
            )
            if (invalidCode) {
                Text(
                    stringResource(R.string.family_scan_invalid),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = spacing.s5).padding(bottom = spacing.s2),
                )
            }

            val executor = remember { Executors.newSingleThreadExecutor() }
            val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
            val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }
            var handled by remember { mutableStateOf(false) }
            DisposableEffect(Unit) {
                onDispose {
                    runCatching { cameraProviderFuture.get().unbindAll() }
                    scanner.close()
                    executor.shutdown()
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        cameraProviderFuture.addListener({
                            val provider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor) { imageProxy ->
                                scanFrame(imageProxy, scanner, alreadyHandled = handled) { bytes ->
                                    handled = true
                                    try {
                                        onScanned(RoomInvite.decode(bytes))
                                    } catch (e: RoomInviteCorrupt) {
                                        handled = false
                                        invalidCode = true
                                    }
                                }
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView
                    },
                )
            }
        }
    }
}

/** @param alreadyHandled dibaca ulang tiap frame (bukan ditangkap sekali) karena ini nilai state Compose. */
// UnsafeOptInUsageError: ImageProxy.image ditandai experimental CameraX lewat anotasi androidx tanpa RequiresOptIn, jadi
//  Kotlin tidak berlaku. Penggunaannya disengaja dan hanya di fungsi ini untuk membaca bingkai QR.
@SuppressLint("UnsafeOptInUsageError")
private fun scanFrame(imageProxy: ImageProxy, scanner: BarcodeScanner, alreadyHandled: Boolean, onValid: (ByteArray) -> Unit) {
    val mediaImage = imageProxy.image
    if (alreadyHandled || mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    scanner.process(image)
        .addOnSuccessListener { barcodes ->
            val raw = barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }?.rawValue ?: return@addOnSuccessListener
            try {
                onValid(Base64.decode(raw, Base64.NO_WRAP))
            } catch (e: IllegalArgumentException) {
                // Base64 tidak valid -- bukan QR undangan Rizqflow, diam-diam diabaikan (bukan "rusak").
            }
        }
        .addOnCompleteListener { imageProxy.close() }
}
