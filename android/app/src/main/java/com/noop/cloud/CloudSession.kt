package com.noop.cloud

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps a valid short-lived access token in hand, transparently rotating it via the refresh token so
 * callers ([CloudSync], the cloud coach, firmware check) never deal with expiry. See noop-cloud Phase 5:
 *
 *   * The access JWT lives ~5 min; this mints a fresh one from the refresh token ~30 s before it lapses
 *     (or on demand after a 401).
 *   * A **transient outage does not disconnect**: the refresh token is durable, so once connectivity
 *     returns within the cloud's 8-hour idle window, the next [validAccessToken] silently refreshes.
 *   * When the refresh token itself is rejected (idle > 8 h, or reuse-revoked), the stored credentials
 *     are cleared and [validAccessToken] returns null → the UI shows "re-link needed".
 *
 * A [Mutex] guards the refresh so concurrent callers don't each spend the single-use refresh token
 * (which would trip the cloud's reuse-detection and revoke the link).
 */
object CloudSession {
    private const val SKEW_SECONDS = 30L  // refresh this early so an in-flight call never races expiry
    private val refreshLock = Mutex()

    /** A currently-valid access token, refreshing if needed. null ⇒ not linked or the link expired. */
    suspend fun validAccessToken(ctx: Context, client: CloudClient = CloudClient(ctx)): String? {
        val app = ctx.applicationContext
        if (!CloudPrefs.isLinked(app)) return null
        val now = System.currentTimeMillis() / 1000
        val access = CloudPrefs.accessToken(app)
        if (!access.isNullOrBlank() && now < CloudPrefs.accessExpiry(app) - SKEW_SECONDS) return access
        return rotate(app, client)
    }

    private suspend fun rotate(app: Context, client: CloudClient): String? = refreshLock.withLock {
        // Re-check under the lock: another caller may have just refreshed while we waited.
        val now = System.currentTimeMillis() / 1000
        val cached = CloudPrefs.accessToken(app)
        if (!cached.isNullOrBlank() && now < CloudPrefs.accessExpiry(app) - SKEW_SECONDS) return cached

        val url = CloudPrefs.baseUrl(app)
        val refresh = CloudPrefs.refreshToken(app) ?: return null

        // Distinguish a DEFINITIVE rejection from a TRANSIENT network error:
        //   * refresh() returns null on a clean 401/410 → the link is dead (idle > 8h or reuse-revoked)
        //     → drop creds so the UI prompts a re-link.
        //   * refresh() THROWS on a network failure (outage) → keep creds untouched; a later call
        //     retries once connectivity returns, within the cloud's 8h window. This is what makes a
        //     transient internet loss NOT disconnect.
        val result = runCatching { client.refresh(url, refresh) }
        val tokens = result.getOrElse { return null }   // transient error: keep creds, just fail this call
        if (tokens == null) {
            CloudPrefs.unlink(app)                       // definitive rejection: link is gone
            return null
        }
        CloudPrefs.saveTokens(app, url, tokens.access, tokens.refresh, tokens.expiresIn)
        return tokens.access
    }
}
