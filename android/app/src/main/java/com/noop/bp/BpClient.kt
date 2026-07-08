package com.noop.bp

import android.content.Context
import com.noop.cloud.CloudPrefs
import com.noop.cloud.CloudSession
import com.noop.cloud.CloudTls
import com.noop.net.NetGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends the strap PPG window ([PpgBuffer]) to the linked noop-cloud for a **cuffless blood-pressure
 * estimate** — an experimental wellness estimate, not a medical measurement. The cloud owns the model;
 * the app only forwards the waveform it already has and (for calibration) a real cuff reading.
 */
object BpClient {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val base = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    sealed interface Result {
        data class Estimate(val systolic: Int, val diastolic: Int, val confidence: Double) : Result
        data object Calibrated : Result
        data object NotLinked : Result
        data object NoSignal : Result
        data class Error(val message: String) : Result
    }

    /** Estimate BP from the most recent PPG window. Requires a prior calibration on the cloud. */
    suspend fun estimate(ctx: Context): Result = post(ctx, "/api/bp/estimate", null, null)

    /** Calibrate the cloud model with a real cuff reading + the concurrent PPG window. */
    suspend fun calibrate(ctx: Context, systolic: Int, diastolic: Int): Result =
        post(ctx, "/api/bp/calibrate", systolic, diastolic)

    private suspend fun post(ctx: Context, path: String, sys: Int?, dia: Int?): Result = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val url = CloudPrefs.baseUrl(app).trimEnd('/')
        if (url.isBlank() || !CloudPrefs.isLinked(app)) return@withContext Result.NotLinked
        val token = CloudSession.validAccessToken(app) ?: return@withContext Result.NotLinked
        val samples = PpgBuffer.window(12) ?: return@withContext Result.NoSignal

        val body = JSONObject()
            .put("ppg", JSONArray(samples))
            .put("fs", PpgBuffer.sampleRateHz)
            .apply { if (sys != null && dia != null) { put("systolic", sys); put("diastolic", dia) } }
            .toString()

        val guarded = runCatching { NetGuard.requireLanOrHttps(url); url }
            .getOrElse { return@withContext Result.Error(it.message ?: "invalid cloud URL") }
        val req = Request.Builder().url("$guarded$path")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON)).build()
        val http = CloudTls.client(app, base)
        runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    return@withContext Result.Error(msg?.ifBlank { "HTTP ${resp.code}" } ?: "HTTP ${resp.code}")
                }
                if (sys != null) return@withContext Result.Calibrated
                val j = JSONObject(text)
                Result.Estimate(j.getInt("systolic"), j.getInt("diastolic"), j.optDouble("confidence", 0.0))
            }
        }.getOrElse { Result.Error("request failed: ${it.message}") }
    }
}
