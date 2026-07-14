package com.noop.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the self-hosted **noop-cloud** pairing API — links this app to the user's own cloud with
 * a short rotating code, the way a TV app pairs (RFC 8628 device authorization grant):
 *
 *   [startPairing] → show [PairStart.userCode] + [PairStart.verificationUri] to the user, who approves
 *   it on the cloud's /pair page with the admin PIN → poll [pollToken] until it returns a token.
 *
 * The cloud base URL is whatever the user typed in Settings (e.g. http://192.168.1.20:8089). This is
 * the app fork's own optional networked feature; noop core stays local.
 */
class CloudClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) {
    /** Build a client that also pins the cloud's self-signed cert (TOFU) for https:// bases. Pass an
     *  app [Context]; for plain http:// bases the pinning layer is inert, so this is always safe. */
    constructor(ctx: android.content.Context) : this(
        CloudTls.client(
            ctx,
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build(),
        ),
    )

    data class PairStart(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val intervalSeconds: Int,
        val expiresInSeconds: Int,
    )

    /** The link credentials: a short-lived access JWT + the rotating refresh token that re-mints it,
     *  plus (on first pairing, when the cloud runs TLS) the mutual-TLS client cert + key. */
    data class Tokens(
        val access: String,
        val refresh: String,
        val expiresIn: Long,
        val clientCert: String? = null,
        val clientKey: String? = null,
    )

    /** Result of one token poll: still waiting, linked (tokens), or the code expired. */
    sealed interface PollResult {
        data object Pending : PollResult
        data class Linked(val tokens: Tokens) : PollResult
        data class Expired(val message: String) : PollResult
    }

    private fun parseTokens(text: String): Tokens {
        val j = JSONObject(text)
        return Tokens(
            access = j.getString("access_token"),
            refresh = j.getString("refresh_token"),
            expiresIn = j.optLong("expires_in", 300L),
            clientCert = j.optString("client_cert").ifBlank { null },
            clientKey = j.optString("client_key").ifBlank { null },
        )
    }

    /** Validate the cloud URL (blocks public-internet cleartext) and normalise its trailing slash. */
    private fun base(baseUrl: String): String {
        com.noop.net.NetGuard.requireLanOrHttps(baseUrl)
        return baseUrl.trimEnd('/')
    }

    /** The cloud's identity + wire-contract version, from the public /api/version handshake. */
    data class ServerVersion(val service: String, val version: String, val api: Int, val minAppApi: Int)

    /** GET /api/version — confirms the cloud is reachable AND reports its version for compat checking. */
    suspend fun serverVersion(baseUrl: String): ServerVersion = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("${base(baseUrl)}/api/version").get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw CloudException("cloud not reachable (HTTP ${resp.code})")
            val j = JSONObject(text)
            ServerVersion(
                service = j.optString("service", "noop-cloud"),
                version = j.optString("version", "?"),
                api = j.optInt("api", 0),
                minAppApi = j.optInt("min_app_api", 0),
            )
        }
    }

    /** POST /pair/start — begins a pairing attempt; the cloud mints a fresh rotating user_code. */
    suspend fun startPairing(baseUrl: String): PairStart = withContext(Dispatchers.IO) {
        val b = base(baseUrl)
        val req = Request.Builder()
            .url("$b/pair/start")
            .post("{}".toRequestBody(JSON))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw CloudException("pairing start failed (HTTP ${resp.code}): ${text.take(160)}")
            val j = JSONObject(text)
            PairStart(
                deviceCode = j.getString("device_code"),
                userCode = j.getString("user_code"),
                verificationUri = j.optString("verification_uri", "$b/pair"),
                intervalSeconds = j.optInt("interval", 3),
                expiresInSeconds = j.optInt("expires_in", 300),
            )
        }
    }

    /** POST /pair/token — one poll. 200 → linked, 202 → pending, 410 → expired. */
    suspend fun pollToken(baseUrl: String, deviceCode: String): PollResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("device_code", deviceCode).toString().toRequestBody(JSON)
        val req = Request.Builder().url("${base(baseUrl)}/pair/token").post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when (resp.code) {
                200 -> PollResult.Linked(parseTokens(text))
                202 -> PollResult.Pending
                410 -> PollResult.Expired("code expired — start again")
                else -> throw CloudException("poll failed (HTTP ${resp.code}): ${text.take(160)}")
            }
        }
    }

    /** POST /pair/refresh — trade the refresh token for a new access JWT + a rotated refresh token.
     *  Returns null if the refresh token is expired/revoked (the app must re-pair). */
    suspend fun refresh(baseUrl: String, refreshToken: String): Tokens? = withContext(Dispatchers.IO) {
        val body = JSONObject().put("refresh_token", refreshToken).toString().toRequestBody(JSON)
        val req = Request.Builder().url("${base(baseUrl)}/pair/refresh").post(body).build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> parseTokens(resp.body?.string().orEmpty())
                401, 410 -> null
                else -> throw CloudException("refresh failed (HTTP ${resp.code})")
            }
        }
    }

    companion object {
        /** The wire-contract version this app speaks. Must match the cloud's `api` to link. */
        const val APP_API_VERSION = 1

        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Compatible when the cloud speaks our exact contract AND accepts an app this old. */
        fun isCompatible(v: ServerVersion): Boolean =
            v.api == APP_API_VERSION && APP_API_VERSION >= v.minAppApi

        /** A user-facing reason when [isCompatible] is false. */
        fun incompatibilityReason(v: ServerVersion): String = when {
            v.api > APP_API_VERSION || APP_API_VERSION < v.minAppApi ->
                "This cloud is newer (API ${v.api}) than the app supports (API $APP_API_VERSION). Update the app."
            v.api < APP_API_VERSION ->
                "This cloud is older (API ${v.api}) than the app expects (API $APP_API_VERSION). Update the cloud."
            else -> "Cloud version incompatible."
        }
    }
}

class CloudException(message: String) : Exception(message)
