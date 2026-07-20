package com.noop.ble

import com.noop.protocol.ParsedFrame

/** Thread-safe device-clock correlation shared by BLE routing and historical decode. */
class ClockReference(initialWall: Int = nowUnix()) {
    @Volatile
    var current: ClockRef = ClockRef.identity(initialWall)
        private set

    @Volatile
    var correlated: Boolean = false
        private set

    /** Accept the first valid decoded clock reply from this connection. */
    @Synchronized
    fun accept(parsed: ParsedFrame, wall: Int = nowUnix()): ClockRef? {
        if (correlated || !parsed.ok || parsed.crcOk == false) return null
        val device = (parsed.parsed["clock"] as? Number)?.toInt() ?: return null
        return ClockRef(device = device, wall = wall).also {
            current = it
            correlated = true
        }
    }

    /** Clear a prior connection's offset before another strap can send data. */
    @Synchronized
    fun reset(wall: Int = nowUnix()) {
        current = ClockRef.identity(wall)
        correlated = false
    }

    private companion object {
        fun nowUnix(): Int = (System.currentTimeMillis() / 1000L).toInt()
    }
}

/** Device RTC and phone wall-clock seconds captured from one GET_CLOCK reply. */
data class ClockRef(val device: Int, val wall: Int) {
    companion object {
        fun identity(wall: Int): ClockRef = ClockRef(device = wall, wall = wall)
    }
}
