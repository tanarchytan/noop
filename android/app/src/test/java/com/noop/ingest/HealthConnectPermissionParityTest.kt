package com.noop.ingest

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The manifest must declare exactly the Health Connect permissions the code asks for at runtime.
 *
 * Both directions break silently. An undeclared permission can never be granted, so the read simply
 * returns nothing forever; a declared one with no reader is dead weight that Health Connect's review
 * rejects. Neither shows up as a test failure or a crash, which is how `READ_DISTANCE` stayed missing
 * while `DistanceRecord` sat in the read set.
 */
class HealthConnectPermissionParityTest {

    private val manifest = File("src/main/AndroidManifest.xml")

    /** Every `android.permission.health.*` the manifest declares. */
    private fun declared(): Set<String> =
        Regex("""android\.permission\.health\.([A-Z0-9_]+)""")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()

    /** Every permission the app actually requests, from the three runtime sets. */
    private fun required(): Set<String> =
        (HealthConnectImporter.PERMISSIONS +
            HealthConnectWriter.PERMISSIONS +
            HealthConnectWriter.EXERCISE_PERMISSIONS)
            .map { it.substringAfterLast('.') }
            .toSet()

    @Test
    fun `manifest declares exactly the permissions the code requests`() {
        assumeTrue("manifest not on the test working dir: ${manifest.absolutePath}", manifest.exists())
        val declared = declared()
        val required = required()
        assertEquals("declared but never requested", emptySet<String>(), declared - required)
        assertEquals("requested but never declared", emptySet<String>(), required - declared)
    }
}
