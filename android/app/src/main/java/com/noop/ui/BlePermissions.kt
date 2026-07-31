package com.noop.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The runtime permissions a BLE scan needs on this OS version. Android 12+ (API 31) uses the
 * granular Bluetooth permissions; API <= 30 falls back to fine location, which the platform
 * requires before it will hand back BLE scan results.
 */
fun blePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/**
 * Requests ACCESS_FINE_LOCATION (needed for GPS-tracked workouts) and reports the outcome. Unlike
 * the BLE permissions, fine location is NOT implicitly granted on Android 12+ — BLE uses the granular
 * Bluetooth permissions there — so a GPS workout must request it explicitly before starting, or
 * `requestLocationUpdates` throws SecurityException and crashes the app. Mirrors [rememberRequestScan];
 * the launcher must live in the Compose layer so it can raise the system dialog. (#101)
 */
@Composable
fun rememberRequestLocation(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> onResult(granted) }
    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            onResult(true)
        } else {
            launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
}
