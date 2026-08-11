package com.noop.ui

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import com.noop.R
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
        Option(null, R.string.language_system_default),
        Option("en", R.string.uicore_language_english),
        Option("es", R.string.uicore_language_spanish),
    )

    /**
     * Every language with a `values-xx` folder, which is a wider set than [SUPPORTED] offers. A system
     * language outside this set gets English strings, so its dates and numbers must read English too:
     * a Dutch phone rendered an English UI over `di, 11 aug` until this existed.
     */
    private val TRANSLATED = setOf("en", "de", "es")

    /** One selectable language: its BCP-47 tag and the name shown for it. A language name is an
     *  endonym, so [labelRes] carries the same word in every locale. */
    data class Option(val tag: String?, @StringRes val labelRes: Int)

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
     * The locale the app actually renders in: the chosen language, else the system's if we translate
     * it (region kept, so an en-GB phone keeps British dates), else English. A stored tag no longer in
     * [SUPPORTED] resolves as if nothing were stored rather than pinning a language the Profile row
     * cannot name.
     */
    fun resolved(base: Context): Locale =
        resolve(runCatching { tag(base) }.getOrNull(), base.resources.configuration.locales[0])

    /** [resolved] without a Context, so the three branches can be pinned by a test. */
    fun resolve(storedTag: String?, system: Locale): Locale {
        if (storedTag != null && SUPPORTED.any { it.tag == storedTag }) {
            return Locale.forLanguageTag(storedTag)
        }
        return if (system.language in TRANSLATED) system else Locale.ENGLISH
    }

    /**
     * The base context rendering in [resolved]. Also the one place `Locale.getDefault` is set, which is
     * what the date and number formatters read — they must not follow a system language the strings do
     * not.
     */
    fun wrap(base: Context): Context {
        val locale = resolved(base)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
