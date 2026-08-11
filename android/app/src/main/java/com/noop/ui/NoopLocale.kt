package com.noop.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's UI language, chosen in Profile and applied in [MainActivity.attachBaseContext].
 *
 * Done here rather than through AppCompat's `setApplicationLocales`: that needs androidx.appcompat,
 * which would pull the legacy view toolkit into a Compose-only app for one setting. This is the same
 * behaviour in a `createConfigurationContext` wrap, and it works on every supported API level.
 */
object NoopLocale {

    /** Persisted BCP-47 tag, or absent for "follow the system". */
    private const val KEY_LANGUAGE = "noop.ui.language"

    /**
     * The languages the app ships a complete UI for. `null` = follow the system.
     *
     * `values-de` exists but covers 120 of 2,309 strings, so German is not offered here — a phone set
     * to German still picks it up through the system, which is where a partial locale belongs.
     */
    val SUPPORTED: List<Option> = listOf(
        Option(null, "System default"),
        Option("en", "English"),
        Option("es", "Español"),
    )

    /** One selectable language: its BCP-47 tag and the name shown for it. */
    data class Option(val tag: String?, val label: String)

    /** The stored tag, or null when following the system. */
    fun tag(context: Context): String? =
        NoopPrefs.of(context).getString(KEY_LANGUAGE, null)

    /** The option currently in force, for the Profile row's trailing value. */
    fun current(context: Context): Option {
        val t = tag(context)
        return SUPPORTED.firstOrNull { it.tag == t } ?: SUPPORTED.first()
    }

    /**
     * Persist a language. The caller must recreate the activity for it to take effect — the wrap
     * happens in `attachBaseContext`, which only runs on activity creation.
     */
    fun set(context: Context, tag: String?) {
        NoopPrefs.of(context).edit().apply {
            if (tag == null) remove(KEY_LANGUAGE) else putString(KEY_LANGUAGE, tag)
        }.apply()
    }

    /**
     * The base context with the chosen locale applied, or unchanged when following the system. A
     * stored tag no longer in [SUPPORTED] falls back to the system rather than pinning a locale the
     * Profile row can no longer name.
     */
    fun wrap(base: Context): Context {
        val tag = runCatching { tag(base) }.getOrNull() ?: return base
        if (SUPPORTED.none { it.tag == tag }) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
