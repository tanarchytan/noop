package com.noop.protocol

import java.util.zip.CRC32

/**
 * Standard zlib CRC-32 over `data[from until to]`, returned in 0..0xFFFFFFFF.
 *
 * Fixture support only: the frames these tests feed through the decode path carry a real CRC-32
 * trailer, so a synthesised frame has to carry one too. Inbound gating is whoop-rs'.
 */
internal fun testCrc32(data: ByteArray, from: Int = 0, to: Int = data.size): Long =
    CRC32().apply { update(data, from, to - from) }.value
