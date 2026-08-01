package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A fresh install owns NO dataset until real data arrives. The store used to seed a `my-whoop` row on
 * create, so every new user met a band that does not exist and the app's null-active fallback resolved
 * to it. The seed is gone; a strap's row is minted by [DeviceRegistry.adoptStrap] on first connect.
 */
class FreshInstallEmptyRegistryTest {

    private fun databaseSource(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            "src/main/java/com/noop/data/WhoopDatabase.kt",
            "app/src/main/java/com/noop/data/WhoopDatabase.kt",
            "android/app/src/main/java/com/noop/data/WhoopDatabase.kt",
        ).map { File(userDir, it) }.firstOrNull { it.isFile }
    }

    /** The store must write no `pairedDevice` row on create. Guarded on the builder's own text because
     *  it needs a Context and this project ships no Robolectric; MIGRATION_7_8's historical seed is
     *  outside it and must stay. */
    @Test
    fun theStoreSeedsNoDeviceOnCreate() {
        val file = databaseSource()
        assertNotNull("WhoopDatabase.kt not reachable from ${System.getProperty("user.dir")}", file)
        val source = file!!.readText()
        val at = source.indexOf("Room.databaseBuilder")
        assertTrue("scanner sanity: the builder must be in this file", at > 0)
        val builder = source.substring(at, source.indexOf(".build()", at))
        assertFalse("no create-time callback: $builder", builder.contains("addCallback"))
        assertFalse("no create-time seed", source.contains("freshInstallBucketSql"))
    }

    /** An empty registry still reads the import sink, so anything banked before a strap exists — a WHOOP
     *  export, a pre-registry write — is visible rather than orphaned. */
    @Test
    fun anEmptyRegistryStillReadsTheImportSink() {
        assertEquals(
            listOf(WhoopRepository.WHOOP_SOURCE),
            WhoopRepository.importedSourceIdsFor(emptyList()),
        )
    }

    /** And it offers nothing to connect to: an honest empty state, not a phantom band. */
    @Test
    fun anEmptyRegistryOffersNoDevice() {
        assertTrue(connectableDevices(emptyList()).isEmpty())
    }

    /** The import sink is a dataset whatever kind an old install left on it — never a device, so it can
     *  never be offered as one again. */
    @Test
    fun theImportSinkIsNeverADevice() {
        val sink = deviceRow(WhoopRepository.WHOOP_SOURCE, DeviceStatus.active, kind = SourceKind.liveBLE)
        assertFalse("the sink has no strap to connect to", isDeviceRow(sink))
        assertTrue(connectableDevices(listOf(sink)).isEmpty())
        assertTrue("but it stays readable", WhoopRepository.WHOOP_SOURCE in WhoopRepository.importedSourceIdsFor(listOf(sink)))
    }
}
