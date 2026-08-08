package com.noop.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * The translation contract, across every locale dir and every `strings*.xml` in it, not the one file.
 *
 * Scope matters here: this gate used to read `values/strings.xml` alone, which was the whole of the
 * externalized app. The UI extraction moves ~3,500 strings into per-area `strings_<area>.xml`, so a
 * single-file check would keep passing while covering a few percent of the screens.
 *
 * What it FAILS on, and why each is a real defect rather than unfinished work:
 *  - a blank translation — renders as nothing on screen, worse than falling back to English;
 *  - an ORPHAN translated key with no English source — a rename left the translation behind, so the
 *    string silently reverted to English in every locale and nothing said so;
 *  - `app_name` redeclared — the brand stays one English value;
 *  - a nav label that is not actually translated, so a locale file cannot be an English copy.
 *
 * What it REPORTS but does not fail: keys with no translation yet. A missing key falls back to English,
 * which is correct behaviour while a locale is being filled in. The coverage figure is printed so the
 * gap is visible instead of implied.
 */
class GermanLocalizationTest {

    private fun resDir(): File? {
        val userDir = File(System.getProperty("user.dir"))
        return listOf(
            File(userDir, "src/main/res"),
            File(userDir, "android/app/src/main/res"),
            File(userDir, "app/src/main/res"),
        ).firstOrNull { it.exists() }
    }

    /** Every `strings*.xml` under one `values*` dir, merged. Android merges them at build time too. */
    private fun parseLocale(res: File, dir: String): Map<String, String> {
        val d = File(res, dir)
        if (!d.exists()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        d.listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { out.putAll(parseStrings(it)) }
        return out
    }

    /** Parse a strings.xml into name -> value (translatable string elements only). */
    private fun parseStrings(file: File): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        // kXML2 directly: android.jar ships a stub XmlPullParserFactory that throws on the JVM classpath.
        val parser = Class.forName("org.kxml2.io.KXmlParser")
            .getDeclaredConstructor().newInstance() as XmlPullParser
        file.inputStream().use { input ->
            parser.setInput(input, "UTF-8")
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "string") {
                    val name = parser.getAttributeValue(null, "name")
                    val value = parser.nextText()
                    if (name != null) out[name] = value
                }
                event = parser.next()
            }
        }
        return out
    }

    private fun localeDirs(res: File): List<String> =
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }
            ?.map { it.name }?.sorted() ?: emptyList()

    @Test
    fun everyTranslationIsWellFormedAndCoverageIsReported() {
        val res = resDir()
        assumeTrue("res/ not found from user.dir=${System.getProperty("user.dir")}", res != null)
        val en = parseLocale(res!!, "values")
        assertTrue("no English strings found", en.isNotEmpty())

        for (dir in localeDirs(res)) {
            val loc = parseLocale(res, dir)
            if (loc.isEmpty()) continue

            val blank = loc.filterValues { it.isBlank() }.keys
            assertTrue("$dir has blank values for: $blank", blank.isEmpty())

            // A translation whose English key no longer exists: the source was renamed or deleted and
            // this one stayed, so the string fell back to English everywhere with nothing reporting it.
            val orphans = loc.keys.filter { it !in en }
            assertTrue("$dir translates keys that no longer exist in English: $orphans", orphans.isEmpty())

            assertFalse("app_name must not be redeclared in $dir", "app_name" in loc)

            val translatable = en.keys.count { it != "app_name" }
            val covered = en.keys.count { it != "app_name" && it in loc }
            println("i18n $dir: $covered / $translatable keys (${covered * 100 / translatable}%)")
        }
    }

    @Test
    fun germanNavLabelsAreActuallyTranslated() {
        val res = resDir()
        assumeTrue("res/ not found", res != null)
        val en = parseLocale(res!!, "values")
        val de = parseLocale(res, "values-de")
        assumeTrue("no German strings", de.isNotEmpty())

        // Spot-check terms that MUST differ, so a locale file cannot be an accidental English copy.
        val differs = mapOf(
            "nav_today" to "Heute",
            "nav_sleep" to "Schlaf",
            "nav_settings" to "Einstellungen",
            "nav_more" to "Mehr",
            "nav_health" to "Gesundheit",
        )
        for ((key, expected) in differs) {
            assertTrue("$key present in en", key in en)
            assertTrue(
                "$key should be German ($expected), was '${de[key]}'",
                de[key] == expected && de[key] != en[key],
            )
        }
    }
}
