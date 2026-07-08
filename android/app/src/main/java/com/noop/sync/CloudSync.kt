package com.noop.sync

import android.content.Context
import com.noop.NoopApplication
import com.noop.cloud.CloudPrefs
import com.noop.cloud.CloudSession
import com.noop.data.DailyMetric
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
import kotlin.math.roundToInt

/**
 * Opt-in one-way push of the app's computed daily metrics to the user's linked **noop-cloud**, so the
 * cloud dashboard (and its coach) have the same numbers the app shows. This is the app fork's own
 * networked feature; noop core stays local — nothing here runs unless the user linked a cloud AND
 * turned sync on ([CloudPrefs.syncEnabled]).
 *
 * Self-contained: it reads the app's own repository + active device and POSTs to `<cloud>/api/sync`
 * with the pairing bearer token. The cloud upserts per-day, so re-syncing the same day is idempotent.
 */
object CloudSync {
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val baseHttp = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        data class Synced(val days: Int) : Result
        data object NotLinked : Result
        data class Error(val message: String) : Result
    }

    /** Gather every computed day and push it. Safe to call often (server upserts by day). */
    suspend fun syncNow(context: Context): Result = withContext(Dispatchers.IO) {
        val ctx = context.applicationContext
        val base = CloudPrefs.baseUrl(ctx).trimEnd('/')
        if (base.isBlank() || !CloudPrefs.isLinked(ctx)) return@withContext Result.NotLinked
        // Auto-rotating access token: refreshes transparently, survives a transient outage, returns
        // null only if the link truly expired (idle > 8h / revoked).
        val token = CloudSession.validAccessToken(ctx)
            ?: return@withContext Result.NotLinked

        val app = ctx as? NoopApplication ?: return@withContext Result.Error("app context unavailable")
        val days = runCatching { app.repository.daysMerged(app.activeDeviceId) }
            .getOrElse { return@withContext Result.Error("could not read metrics: ${it.message}") }
        if (days.isEmpty()) return@withContext Result.Synced(0)

        val body = JSONObject().put("days", JSONArray().apply {
            days.forEach { put(dailyJson(it)) }
        }).toString()

        val guarded = runCatching { NetGuard.requireLanOrHttps(base); base }
            .getOrElse { return@withContext Result.Error(it.message ?: "invalid cloud URL") }

        val req = Request.Builder()
            .url("$guarded/api/sync")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON))
            .build()
        val http = com.noop.cloud.CloudTls.client(ctx, baseHttp)  // pins the cloud cert for https bases
        runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@withContext Result.Error("cloud HTTP ${resp.code}: ${text.take(140)}")
                val n = runCatching { JSONObject(text).optInt("days", days.size) }.getOrDefault(days.size)
                Result.Synced(n)
            }
        }.getOrElse { Result.Error("sync failed: ${it.message}") }
    }

    /** Map one computed day to the cloud's sync shape. Only non-null metrics are sent, so a partial
     *  day never overwrites a previously-synced value with null (matches the server's partial upsert). */
    private fun dailyJson(d: DailyMetric): JSONObject {
        val o = JSONObject().put("day", d.day).put("provenance", "band")
        d.recovery?.let { o.put("recovery", it.roundToInt()) }
        // Sleep performance (the Rest composite 0–100) isn't a DailyMetric column; derive it from the
        // same banked totals via the single source of truth so the dashboard's Sleep ring fills.
        com.noop.analytics.RestScorer.restFromDaily(d)?.let { o.put("sleep_perf", it.roundToInt()) }
        d.strain?.let { o.put("strain", it) }
        d.restingHr?.let { o.put("resting_hr", it) }
        d.avgHrv?.let { o.put("hrv", it) }
        d.spo2Pct?.let { o.put("spo2", it) }
        d.skinTempDevC?.let { o.put("skin_temp", it) }
        d.respRateBpm?.let { o.put("respiratory", it) }
        d.steps?.let { o.put("steps", it) }
        return o
    }
}
