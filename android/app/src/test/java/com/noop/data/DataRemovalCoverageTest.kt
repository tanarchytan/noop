package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The removal selector must be able to delete everything the "remove all" path deletes.
 *
 * `DeviceRegistryTest` already forces every deviceId-keyed table into `deleteAllDataRows`. This adds
 * the other half: every one of those tables must also sit in exactly one [DataCategory] arm, so
 * ticking every box removes exactly what "remove all" removes. A table added later fails here.
 */
class DataRemovalCoverageTest {

    private fun registrySource(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(userDir, "src/main/java/com/noop/data/DeviceRegistry.kt"),
            File(userDir, "app/src/main/java/com/noop/data/DeviceRegistry.kt"),
            File(userDir, "android/app/src/main/java/com/noop/data/DeviceRegistry.kt"),
        )
        // Deliberately NOT assumeTrue: the source is always present, and skipping would report a pass
        // for a check that never ran.
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError("DeviceRegistry.kt not found; looked in \$candidates")
    }

    /** The body of `fun name(...)`, by brace matching from its opening brace. */
    private fun functionBody(source: String, name: String): String {
        val at = source.indexOf("fun $name(")
        assertTrue("function not found: $name", at >= 0)
        val open = source.indexOf('{', at)
        assertTrue("no body for $name", open >= 0)
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unterminated body for $name")
    }

    private val deleteCall = Regex("""dao\.(delete[A-Za-z0-9]+For)\(""")

    private fun deletesIn(body: String): Set<String> =
        deleteCall.findAll(body).map { it.groupValues[1] }.toSet()

    @Test
    fun `every table the remove-all path deletes is in exactly one category`() {
        val source = registrySource().readText()
        val all = deletesIn(functionBody(source, "deleteAllDataRows"))
        val categorised = deletesIn(functionBody(source, "deleteCategoryRows"))

        assertTrue("deleteAllDataRows deletes nothing — the regex or the function moved", all.isNotEmpty())
        assertEquals(
            "tables removable by 'remove all' but not reachable from any category",
            emptySet<String>(),
            all - categorised,
        )
        assertEquals(
            "categories delete a table 'remove all' does not — one of them is wrong",
            emptySet<String>(),
            categorised - all,
        )
    }

    @Test
    fun `no table is claimed by two categories`() {
        val body = functionBody(registrySource().readText(), "deleteCategoryRows")
        val calls = deleteCall.findAll(body).map { it.groupValues[1] }.toList()
        val duplicated = calls.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("a table appears in more than one category", emptySet<String>(), duplicated)
    }

    @Test
    fun `every category has at least one table`() {
        val body = functionBody(registrySource().readText(), "deleteCategoryRows")
        for (category in DataCategory.ordered) {
            assertTrue(
                "category ${category.name} has no arm in deleteCategoryRows",
                body.contains("DataCategory.${category.name} ->"),
            )
        }
    }
}
