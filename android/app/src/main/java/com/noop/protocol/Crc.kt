package com.noop.protocol

/**
 * Frame checksum for the WHOOP wire protocol.
 *
 *  - [crc32]: standard zlib CRC-32 (reflected, poly 0xEDB88320) — guards the frame payload.
 *
 * Inbound-frame CRC gating now happens in the Rust whoop-rs codec (crc.rs); the remaining Kotlin
 * [crc32] is used by the historical-stream tests to synthesise a valid CRC32 trailer for the frames
 * they feed through the KEEP historical decode path.
 *
 * Inputs are treated as raw unsigned bytes. The result is returned widened to `Long` (Kotlin has no
 * unsigned return types in common use) but always carries only the low 32 bits.
 */
object Crc {

    // Standard zlib CRC-32 table (reflected, poly 0xEDB88320), built once at class init.
    private val crc32Table: LongArray = LongArray(256).also { table ->
        for (i in 0 until 256) {
            var c = i.toLong() and 0xFFFFFFFFL
            repeat(8) {
                c = if ((c and 1L) != 0L) 0xEDB88320L xor (c ushr 1) else (c ushr 1)
            }
            table[i] = c and 0xFFFFFFFFL
        }
    }

    /**
     * Standard zlib CRC-32 over `data[from until to]`. Returns a value in 0..0xFFFFFFFF as a Long.
     * The range defaults to the whole array.
     */
    fun crc32(data: ByteArray, from: Int = 0, to: Int = data.size): Long {
        var crc = 0xFFFFFFFFL
        var i = from
        while (i < to) {
            val idx = ((crc xor (data[i].toLong() and 0xFFL)) and 0xFFL).toInt()
            crc = crc32Table[idx] xor (crc ushr 8)
            i++
        }
        return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
    }
}
