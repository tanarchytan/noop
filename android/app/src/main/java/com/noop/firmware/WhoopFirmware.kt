package com.noop.firmware

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * WHOOP firmware DOWNLOAD — fetch the official chip firmware images for a band from WHOOP's
 * cloud, using the user's own WHOOP account.
 *
 * Contract reverse-engineered from openwhoop's `api.rs` (sign-in → firmware-service v4 → a
 * base64 ZIP of chip images). This is the frankenwhoop fork's one optional networked feature;
 * it is OFF by default and reachable only from Devices → Firmware download. It NEVER flashes
 * the band — multi-chip signed DFU is a brick wall — it only downloads + extracts the images
 * to app storage, so the official WHOOP app remains the only way to actually install firmware.
 *
 * Personal use only; ToS-gray (uses the private cloud with your own credentials).
 */

/** WHOOP device family → firmware-service deviceName + chip set (openwhoop main.rs:418). */
enum class WhoopHardware(val deviceName: String, val label: String, val chips: List<String>) {
    HARVARD("HARVARD", "WHOOP 4.0", listOf("MAXIM", "NORDIC")),
    PUFFIN("PUFFIN", "WHOOP (Puffin)", listOf("MAXIM", "NORDIC", "RUGGLES", "PEARL")),
    MAVERICK("MAVERICK", "WHOOP 5.0 / MG", listOf("AMBIQ")),
}

/**
 * Real per-chip target versions to request when the caller doesn't supply one. The server returns
 * HTTP 404 for the 1.0.0.0 placeholder — it needs a real version to "upgrade to". These are
 * openwhoop's documented defaults, VERIFIED live: requesting AMBIQ 50.36.1.0 for MAVERICK returns
 * a real ~1 MB maverick-50.36.1.0.zbin. Unknown chips fall back to 1.0.0.0.
 */
val DEFAULT_CHIP_TARGET: Map<String, String> = mapOf(
    "MAXIM" to "41.16.5.0",
    "NORDIC" to "17.2.2.0",
    "AMBIQ" to "50.36.1.0",
)

data class ChipFirmware(val chipName: String, val version: String)
data class ExtractedFile(val name: String, val size: Long)
data class FirmwareDownloadResult(
    val deviceName: String,
    val zipBytes: Int,
    val files: List<ExtractedFile>,
    val outputDir: String,
    val serverConfig: String?,
)

/**
 * Result of an update check: the band's current firmware vs WHOOP's desired ("latest for this
 * device") config. [updateAvailable] is true when WHOOP wants a newer version than the band has,
 * or a [forceUpdate] is flagged. Check-only — no firmware image is downloaded or saved.
 */
data class UpdateStatus(
    val deviceName: String,
    val current: String,
    val desired: String,
    val forceUpdate: Boolean,
    val updateAvailable: Boolean,
)

class FirmwareException(message: String) : Exception(message)

/**
 * Compare dotted numeric firmware versions (e.g. "50.40.1.0" vs "50.36.1.0"). >0 if [a] is newer,
 * <0 if older, 0 if equal. Single source of truth for firmware version ordering (used by the
 * WHOOP-login update check here and the no-login cloud check in com.noop.cloud).
 */
fun compareFirmwareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (d != 0) return d
    }
    return 0
}

class WhoopFirmwareClient(
    private val baseUrl: String = "https://api.prod.whoop.com",
    private val http: OkHttpClient = defaultHttp(),
) {
    /** Sign in + download + extract in one call. Download only — never flashes the band. */
    suspend fun download(
        email: String,
        password: String,
        hw: WhoopHardware,
        targetVersions: Map<String, String>,
        outputDir: File,
    ): FirmwareDownloadResult {
        val token = signIn(email, password)
        // current = 1.0.0.0 ("I have nothing — send the full image to reach the target"); the
        // upgrade target must be a real version (placeholder 404s), so fall back to the verified
        // per-chip defaults when the caller leaves it blank.
        val current = hw.chips.map { ChipFirmware(it, "1.0.0.0") }
        val upgrade = hw.chips.map { ChipFirmware(it, targetVersions[it] ?: DEFAULT_CHIP_TARGET[it] ?: "1.0.0.0") }
        val (zip, serverConfig) = downloadFirmware(token, hw.deviceName, current, upgrade)
        val files = extractZip(zip, outputDir)
        return FirmwareDownloadResult(hw.deviceName, zip.size, files, outputDir.absolutePath, serverConfig)
    }

    /**
     * Check whether WHOOP has a newer firmware than the band currently runs — WITHOUT downloading
     * the image. Reads the band's current version (from BLE / Devices, e.g. "50.40.1.0"), asks the
     * firmware-service what it wants the device on ([desired_device_firmware_config]), and compares.
     *
     * We echo the current version as the requested upgrade target: the server's desired config is
     * authoritative regardless, and echoing current avoids the 1.0.0.0-placeholder 404. If WHOOP
     * returns the same version (or no upgrade), the band is up to date.
     */
    suspend fun checkForUpdate(
        email: String,
        password: String,
        hw: WhoopHardware,
        currentVersion: String,
    ): UpdateStatus {
        val token = signIn(email, password)
        val current = hw.chips.map { ChipFirmware(it, currentVersion) }
        val cfg = fetchDesiredConfig(token, hw.deviceName, current)
            ?: return UpdateStatus(hw.deviceName, currentVersion, currentVersion, false, false)
        // Desired = the highest chip version WHOOP wants; compare against the band's current.
        val desired = cfg.chips.map { it.version }.maxWithOrNull(::compareFirmwareVersions) ?: currentVersion
        val newer = compareFirmwareVersions(desired, currentVersion) > 0
        return UpdateStatus(hw.deviceName, currentVersion, desired, cfg.forceUpdate, newer || cfg.forceUpdate)
    }

    private data class DesiredConfig(val chips: List<ChipFirmware>, val forceUpdate: Boolean)

    /** POST the version endpoint and parse ONLY desired_device_firmware_config (no blob decode). */
    private suspend fun fetchDesiredConfig(
        token: String,
        deviceName: String,
        current: List<ChipFirmware>,
    ): DesiredConfig? = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("current_chip_firmwares", chipArray(current))
            .put("chip_firmwares_of_upgrade", chipArray(current))   // echo current — desired config is authoritative
            .toString().toRequestBody(JSON)
        val url = "$baseUrl/firmware-service/v4/firmware/version?deviceName=" +
            URLEncoder.encode(deviceName, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("X-WHOOP-Device-Platform", "ANDROID")
            .post(payload)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            // A 404 here (no upgrade for this exact version) legitimately means "up to date".
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw FirmwareException("update check failed (HTTP ${resp.code}): ${text.take(200)}")
            val cfg = JSONObject(text).optJSONObject("desired_device_firmware_config") ?: return@withContext null
            val chips = ArrayList<ChipFirmware>()
            cfg.optJSONArray("chip_firmwares")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    chips.add(ChipFirmware(c.optString("chip_name"), c.optString("version")))
                }
            }
            DesiredConfig(chips, cfg.optBoolean("force_update"))
        }
    }

    /** POST /auth-service/v2/whoop/sign-in → access token. */
    suspend fun signIn(email: String, password: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("username", email).put("password", password)
            .toString().toRequestBody(JSON)
        val req = Request.Builder()
            .url("$baseUrl/auth-service/v2/whoop/sign-in")
            .header("Accept", "application/json")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw FirmwareException("sign-in failed (HTTP ${resp.code}): ${text.take(200)}")
            JSONObject(text).optString("access_token").ifEmpty { throw FirmwareException("no access_token in auth response") }
        }
    }

    /** POST /firmware-service/v4/firmware/version → (zip bytes, server-config line). */
    private suspend fun downloadFirmware(
        token: String,
        deviceName: String,
        current: List<ChipFirmware>,
        upgrade: List<ChipFirmware>,
    ): Pair<ByteArray, String?> = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("current_chip_firmwares", chipArray(current))
            .put("chip_firmwares_of_upgrade", chipArray(upgrade))
            .toString().toRequestBody(JSON)
        val url = "$baseUrl/firmware-service/v4/firmware/version?deviceName=" +
            URLEncoder.encode(deviceName, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
            .header("X-WHOOP-Device-Platform", "ANDROID")
            .post(payload)
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw FirmwareException("firmware request failed (HTTP ${resp.code}): ${text.take(200)}")
            val json = JSONObject(text)
            val b64 = json.optString("firmware_zip_file").ifEmpty { json.optString("firmware_file") }
            if (b64.isNullOrEmpty()) throw FirmwareException("no firmware file in response")
            Base64.getDecoder().decode(b64) to describeConfig(json.optJSONObject("desired_device_firmware_config"))
        }
    }

    private fun chipArray(list: List<ChipFirmware>) = JSONArray().apply {
        list.forEach { put(JSONObject().put("chip_name", it.chipName).put("version", it.version)) }
    }

    private fun describeConfig(cfg: JSONObject?): String? {
        if (cfg == null) return null
        val sb = StringBuilder("device=").append(cfg.optString("hardware_device", "?"))
        cfg.optJSONArray("chip_firmwares")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                sb.append(' ').append(c.optString("chip_name")).append(':').append(c.optString("version"))
            }
        }
        if (cfg.optBoolean("force_update")) sb.append(" [force_update]")
        return sb.toString()
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        private fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        /** Extract a ZIP byte array into [outDir] (zip-slip guarded). JDK-only. */
        fun extractZip(zipBytes: ByteArray, outDir: File): List<ExtractedFile> {
            outDir.mkdirs()
            File(outDir, "firmware.zip").writeBytes(zipBytes)
            val root = outDir.canonicalPath
            val out = ArrayList<ExtractedFile>()
            ZipInputStream(zipBytes.inputStream()).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    val target = File(outDir, entry.name)
                    val canon = target.canonicalPath
                    if (canon != root && !canon.startsWith(root + File.separator)) {
                        throw SecurityException("zip entry escapes output dir: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        val bytes = zin.readBytes()
                        target.writeBytes(bytes)
                        out.add(ExtractedFile(entry.name, bytes.size.toLong()))
                    }
                    zin.closeEntry()
                    entry = zin.nextEntry
                }
            }
            return out
        }
    }
}
