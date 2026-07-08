package com.noop.cloud

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the noop-cloud base URL + the link credentials, encrypted at rest (Jetpack Security
 * EncryptedSharedPreferences, AES256-GCM). The link uses a short-lived **access** JWT plus a rotating
 * **refresh** token (see noop-cloud Phase 5): the refresh token is the durable credential (survives a
 * transient outage), the access token is re-minted from it every few minutes by [CloudSession]. Both
 * are bearer credentials, so they never sit in plaintext prefs. Mirrors noop's AiKeyStore pattern.
 */
object CloudPrefs {
    private const val FILE = "noop_cloud_secure"
    private const val K_URL = "base_url"
    private const val K_ACCESS = "access_token"
    private const val K_REFRESH = "refresh_token"
    private const val K_ACCESS_EXP = "access_exp"   // epoch seconds when the access token expires
    private const val K_SYNC = "sync_enabled"
    private const val K_CERT_PIN = "cert_pin"       // SHA-256 of the cloud's self-signed cert (TOFU)
    private const val K_CLIENT_CERT = "client_cert" // our mutual-TLS client cert (PEM), minted at pairing
    private const val K_CLIENT_KEY = "client_key"   // its private key (PKCS#8 PEM)

    private fun prefs(ctx: Context) = EncryptedSharedPreferences.create(
        ctx.applicationContext,
        FILE,
        MasterKey.Builder(ctx.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun baseUrl(ctx: Context): String = prefs(ctx).getString(K_URL, "").orEmpty()
    fun accessToken(ctx: Context): String? = prefs(ctx).getString(K_ACCESS, null)
    fun refreshToken(ctx: Context): String? = prefs(ctx).getString(K_REFRESH, null)
    fun accessExpiry(ctx: Context): Long = prefs(ctx).getLong(K_ACCESS_EXP, 0L)

    /** Linked = we hold a refresh token (the durable credential) and a cloud URL. */
    fun isLinked(ctx: Context): Boolean = !refreshToken(ctx).isNullOrBlank() && baseUrl(ctx).isNotBlank()

    fun saveUrl(ctx: Context, url: String) = prefs(ctx).edit().putString(K_URL, url.trim()).apply()

    /** Store the credentials from pairing or a refresh. [expiresIn] is the access-token lifetime (s). */
    fun saveTokens(ctx: Context, url: String?, access: String, refresh: String, expiresIn: Long) {
        val exp = System.currentTimeMillis() / 1000 + expiresIn
        prefs(ctx).edit().apply {
            if (!url.isNullOrBlank()) putString(K_URL, url.trim())
            putString(K_ACCESS, access)
            putString(K_REFRESH, refresh)
            putLong(K_ACCESS_EXP, exp)
        }.apply()
    }

    /** The pinned cloud cert SHA-256 (set trust-on-first-use on the first HTTPS link), or null. */
    fun certPin(ctx: Context): String? = prefs(ctx).getString(K_CERT_PIN, null)
    fun saveCertPin(ctx: Context, sha256: String) = prefs(ctx).edit().putString(K_CERT_PIN, sha256).apply()

    /** Our mutual-TLS client cert + key (PEM), minted by the cloud at pairing. Null if the cloud isn't
     *  running TLS — then the link uses server-cert pinning only. */
    fun clientCert(ctx: Context): String? = prefs(ctx).getString(K_CLIENT_CERT, null)
    fun clientKey(ctx: Context): String? = prefs(ctx).getString(K_CLIENT_KEY, null)
    fun saveClientCert(ctx: Context, cert: String, key: String) =
        prefs(ctx).edit().putString(K_CLIENT_CERT, cert).putString(K_CLIENT_KEY, key).apply()

    /** Unlink: drop the credentials + cert pin + client cert but keep the URL so re-linking is one tap. */
    fun unlink(ctx: Context) = prefs(ctx).edit()
        .remove(K_ACCESS).remove(K_REFRESH).remove(K_ACCESS_EXP).remove(K_CERT_PIN)
        .remove(K_CLIENT_CERT).remove(K_CLIENT_KEY).apply()

    /** Opt-in: whether to auto-push daily metrics to the linked cloud (default off — privacy-first). */
    fun syncEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_SYNC, false)
    fun setSyncEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean(K_SYNC, on).apply()
}
