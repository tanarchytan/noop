package com.noop.cloud

import android.content.Context
import android.provider.Settings
import com.noop.NoopApplication

/**
 * The app's unique cloud client id = **Android device id + WHOOP strap id**. The cloud binds a link to
 * this id at pairing and rejects any request whose id doesn't match (a token copied to another phone,
 * or a different strap, carries a different id). Sent as the `X-Noop-Client-Id` header on every cloud
 * call. If we can't form an id at all, we send empty and the cloud rejects by default.
 */
object ClientId {
    /** `<android_id>:<strap_id>` — stable per (device, strap) pairing. */
    fun get(ctx: Context): String {
        val app = ctx.applicationContext
        val androidId = runCatching {
            Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull().orEmpty()
        val strap = runCatching { (app as NoopApplication).activeDeviceId }.getOrNull().orEmpty()
        return "$androidId:$strap"
    }
}
