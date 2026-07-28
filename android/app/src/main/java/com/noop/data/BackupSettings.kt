package com.noop.data

import android.content.Context
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import com.noop.ui.UnitPrefs
import org.json.JSONObject

/**
 * The `settings.json` payload inside a `.noopbak` backup — a ZIP whose first entry is the SQLite
 * database (round-trips every row). The user's profile (age/sex/weight/height/HR-max override) and
 * display preferences live in SharedPreferences instead, so a restore onto a fresh device would
 * silently reset them; this adds a SECOND, optional ZIP entry carrying one WHITELISTED set of keys.
 *
 * The whitelist allows only stable, user-set, non-device-specific values — never device ids,
 * peripheral ids, tokens, sync cursors, or anything anonymity-sensitive, since backups get shared
 * in cloud folders and issue trackers. Unknown keys are dropped on decode; a backup with no
 * `settings.json` is a DB-only restore. [BackupSettingsCodec] is pure JSON + whitelist (plain-JVM
 * testable, no Context); the SharedPreferences boundary lives in [BackupSettingsBridge] below.
 */
object BackupSettingsCodec {

    /** Canonical entry name inside the `.noopbak` ZIP. */
    const val ENTRY_NAME = "settings.json"

    /** The JSON kind a whitelisted key must decode to. Anything else is dropped, never guessed at. */
    enum class Kind { INT, DOUBLE, STRING }

    /**
     * The only keys `settings.json` may carry: profile body metrics (HR zones/calories/recovery),
     * the HR-max override (`profile.hrMax`, 0 = auto/Tanaka), unit system, a temperature override
     * ("" = match the system), and the Effort axis. Per-strap/device-specific values are excluded.
     */
    val WHITELIST: Map<String, Kind> = linkedMapOf(
        "profile.age" to Kind.INT,
        "profile.sex" to Kind.STRING,
        "profile.weightKg" to Kind.DOUBLE,
        "profile.heightCm" to Kind.DOUBLE,
        "profile.waistCm" to Kind.DOUBLE,
        "profile.hrMax" to Kind.INT,
        "units.system" to Kind.STRING,
        "units.temperature" to Kind.STRING,
        "effort.scale" to Kind.STRING,
    )

    /**
     * Encode the whitelisted subset of [values] as the flat `settings.json` object, or null when
     * nothing whitelisted is present (the exporter then writes a DB-only backup — indistinguishable
     * from a legacy one, which is exactly the right degrade).
     */
    fun encode(values: Map<String, Any?>): String? {
        val obj = JSONObject()
        for ((key, kind) in WHITELIST) {
            val coerced = coerce(values[key], kind) ?: continue
            obj.put(key, coerced)
        }
        return if (obj.length() == 0) null else obj.toString()
    }

    /**
     * Decode a `settings.json` payload down to its whitelisted, correctly-typed subset. Malformed
     * JSON, unknown keys and wrong-typed values all degrade to "fewer keys" — never an error, because
     * a bad settings entry must not fail a restore whose DB half is fine.
     */
    fun decode(json: String): Map<String, Any> {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for ((key, kind) in WHITELIST) {
            if (!obj.has(key)) continue
            coerce(obj.opt(key), kind)?.let { out[key] = it }
        }
        return out
    }

    /**
     * Coerce a JSON-decoded (or caller-supplied) value to the whitelist's declared kind, or null.
     * JSON booleans are not [Number]s on the JVM, so `true` can never become age 1.
     */
    private fun coerce(value: Any?, kind: Kind): Any? = when (kind) {
        Kind.STRING -> value as? String
        Kind.INT -> (value as? Number)?.toInt()
        Kind.DOUBLE -> (value as? Number)?.toDouble()
    }
}

/**
 * The SharedPreferences boundary for [BackupSettingsCodec]: snapshot this device's whitelisted
 * settings for export, and re-apply a restored payload. Kept separate from the codec so the codec
 * stays plain-JVM testable (this object needs a real Context).
 *
 * Storage mapping (canonical key → where it actually lives here):
 *  - `profile.*`  → the `noop_profile` prefs via [ProfileStore.backupSnapshot]/[ProfileStore.applyBackup]
 *                   (canonical `profile.hrMax` ↔ ProfileStore's `hr_max_override`).
 *  - `units.*` / `effort.scale` → [NoopPrefs] under the same literal key strings as the canonical names.
 */
object BackupSettingsBridge {

    /** The whitelisted, user-SET settings of this device as the `settings.json` string, or null. */
    fun snapshotJson(context: Context): String? {
        val values = LinkedHashMap<String, Any>()
        values.putAll(ProfileStore.from(context).backupSnapshot())
        val noop = NoopPrefs.of(context)
        if (noop.contains(NoopPrefs.KEY_UNIT_SYSTEM)) {
            noop.getString(NoopPrefs.KEY_UNIT_SYSTEM, null)?.let { values["units.system"] = it }
        }
        if (noop.contains(NoopPrefs.KEY_TEMPERATURE_UNIT)) {
            noop.getString(NoopPrefs.KEY_TEMPERATURE_UNIT, null)?.let { values["units.temperature"] = it }
        }
        if (noop.contains(UnitPrefs.KEY_EFFORT_SCALE)) {
            noop.getString(UnitPrefs.KEY_EFFORT_SCALE, null)?.let { values["effort.scale"] = it }
        }
        return BackupSettingsCodec.encode(values)
    }

    /**
     * Re-apply a restored `settings.json` to this device. The caller ([DataBackup.importFrom]) invokes
     * this only AFTER the DB swap succeeded, never on a failed or rolled-back restore. Keys absent
     * from the payload leave current values alone; profile setters clamp to normal ranges.
     */
    fun apply(context: Context, json: String) {
        val values = BackupSettingsCodec.decode(json)
        if (values.isEmpty()) return

        ProfileStore.from(context).applyBackup(values)

        val editor = NoopPrefs.of(context).edit()
        (values["units.system"] as? String)?.let { editor.putString(NoopPrefs.KEY_UNIT_SYSTEM, it) }
        (values["units.temperature"] as? String)?.let { raw ->
            // "" means "match the system's length/mass units"; here that state is key-absent.
            if (raw.isEmpty()) editor.remove(NoopPrefs.KEY_TEMPERATURE_UNIT)
            else editor.putString(NoopPrefs.KEY_TEMPERATURE_UNIT, raw)
        }
        (values["effort.scale"] as? String)?.let { editor.putString(UnitPrefs.KEY_EFFORT_SCALE, it) }
        editor.apply()
    }
}
