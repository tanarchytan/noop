package com.noop.ble

import com.noop.data.DeviceRegistry
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dual-connect decision: a strap and a ring hold their links at the same time, and the active
 * device decides only which one's readings reach [LiveState].
 *
 * The radio is not exercised here and cannot be — BLE behaviour is hardware-only. What is pinned is
 * what the coordinator DECIDES: who gets stopped, who keeps streaming, who holds focus. Before this,
 * activating a ring called stopWhoop and activating a strap tore the ring down, so exactly one
 * device could stream.
 */
class SourceCoordinatorDualConnectTest {

    private class FakeSource : LiveHrSource {
        var connectedTo: String? = null
        var stopped = 0
        override fun scan() = Unit
        override fun connect(address: String) { connectedTo = address }
        override fun stop() { stopped++ }
    }

    private class Harness {
        val dao = FakeRegistryDao()
        val ring = FakeSource()
        var whoopStarts = 0
        var whoopStops = 0
        var whoopFocused: Boolean? = null
    }

    private fun row(id: String, brand: String, kind: SourceKind, address: String?, active: Boolean) =
        PairedDeviceRow(
            id = id,
            brand = brand,
            model = if (brand == "Oura") "Oura Ring 4" else "5.0 / MG",
            nickname = null,
            peripheralId = address,
            sourceKind = kind.name,
            capabilities = "hr",
            status = if (active) DeviceStatus.active.name else DeviceStatus.paired.name,
            addedAt = 0L,
            lastSeenAt = 0L,
        )

    /** A strap (active) and a ring, both registered with a stored address. */
    private fun harness(): Harness {
        val h = Harness()
        h.dao.devices["my-whoop"] =
            row("my-whoop", "WHOOP", SourceKind.liveBLE, "AA:BB:CC:DD:EE:FF", active = true)
        h.dao.devices["ring"] =
            row("ring", "Oura", SourceKind.oura, "11:22:33:44:55:66", active = false)
        return h
    }

    private fun coordinator(h: Harness): SourceCoordinator = SourceCoordinator(
        context = null,
        registry = DeviceRegistry(
            h.dao,
            object : DeviceRegistry.Transactor {
                override suspend fun <R> run(block: suspend () -> R): R = block()
            },
        ),
        repository = null,
        liveSink = { _, _, _ -> },
        startWhoop = { h.whoopStarts++ },
        stopWhoop = { h.whoopStops++ },
        setWhoopFocused = { h.whoopFocused = it },
        sourceFactory = { _, _ -> h.ring },
        scope = CoroutineScope(Dispatchers.Unconfined),
    )

    @Test
    fun `activating a ring does not stop the strap`() = runBlocking {
        val h = harness()
        val c = coordinator(h)
        c.start()
        c.onActiveDeviceChanged("ring")

        assertEquals("the strap's link is never dropped for a ring", 0, h.whoopStops)
        assertEquals("the ring connects to its stored address", "11:22:33:44:55:66", h.ring.connectedTo)
        assertTrue("the ring holds focus", c.isFocused("ring"))
        assertFalse("the strap yields the displayed reading", c.isFocused("my-whoop"))
        assertEquals("the strap is told it no longer projects", false, h.whoopFocused)
    }

    @Test
    fun `focusing the strap again leaves the ring streaming`() = runBlocking {
        val h = harness()
        val c = coordinator(h)
        c.start()
        c.onActiveDeviceChanged("ring")
        c.onActiveDeviceChanged("my-whoop")

        assertEquals("the ring keeps its link when focus moves away", 0, h.ring.stopped)
        assertTrue("the strap holds focus again", c.isFocused("my-whoop"))
        assertEquals("the strap is told it projects again", true, h.whoopFocused)
    }

    @Test
    fun `removing a device stops only that source`() = runBlocking {
        val h = harness()
        val c = coordinator(h)
        c.start()
        c.onActiveDeviceChanged("ring")
        c.stopSource("ring")

        assertEquals("the removed ring is torn down exactly once", 1, h.ring.stopped)
        assertEquals("and the strap is untouched", 0, h.whoopStops)
    }
}
