package com.noop.ble

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WhoopBleClockWiringStructureTest {
    private fun source(): String? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(userDir, "src/main/java/com/noop/ble/WhoopBleClient.kt"),
            File(userDir, "app/src/main/java/com/noop/ble/WhoopBleClient.kt"),
            File(userDir, "android/app/src/main/java/com/noop/ble/WhoopBleClient.kt"),
        ).firstOrNull(File::isFile)?.readText()
    }

    @Test
    fun clockRepliesStayOnCurrentGattAndWhoop4Path() {
        val source = source()
        assumeTrue("WhoopBleClient source unavailable", source != null)

        assertTrue(source!!.contains("connectedFamily == DeviceFamily.WHOOP4"))
        assertTrue(source.contains("clockReference.accept(parsed)"))
        assertTrue(source.contains("clockReference.reset()"))
        assertTrue(source.windowed("if (g !== this@WhoopBleClient.gatt) return".length)
            .count { it == "if (g !== this@WhoopBleClient.gatt) return" } >= 2)
    }
}
