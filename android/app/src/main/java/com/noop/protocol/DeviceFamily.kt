package com.noop.protocol

/**
 * Which WHOOP hardware generation a connection / capture belongs to.
 *
 * Platform-pure: it never imports Android Bluetooth types, so the protocol code runs on a plain JVM
 * (and in tests). The GATT service / characteristic UUIDs live in the BLE client, not here.
 */
enum class DeviceFamily {
    /** Whoop 4.0 — 0x07 CRC8 header check. */
    WHOOP4,

    /** Whoop 5.0 / MG — CRC16-Modbus header check, "puffin" packet types. */
    WHOOP5;

    /**
     * Static CLIENT_HELLO frame this family writes immediately after GATT discovery to start a
     * session, or `null` for families that do not use a fixed hello. The 5.0 hello is a fully-formed
     * type-35 (COMMAND) frame with a CRC16-Modbus header and a CRC32 payload trailer.
     */
    val clientHello: ByteArray?
        get() = when (this) {
            WHOOP4 -> null
            WHOOP5 -> WHOOP5_CLIENT_HELLO.copyOf()
        }

    companion object {
        /**
         * Resolve a device-registry `model` label to the strap family that wrote its rows.
         *
         * The registry holds several historical spellings for the same hardware: the Add-Device
         * wizard stores bare "4.0" / "5.0 MG", other paths match the full picker labels
         * ("WHOOP 4.0" / "WHOOP 5.0 / MG"), and the legacy seeded "my-whoop" row stores just
         * "WHOOP". Matching any single spelling silently misses the others, so this is
         * the ONE place allowed to interpret registry model labels.
         *
         * "WHOOP" predates the wizard and was written identically for 4.0 and 5/MG installs, so
         * it carries no family information; it keeps the prior WHOOP5 fallback, as do
         * null/unknown labels (non-WHOOP imports whose skin temp is already °C) — only a
         * positively-identified 4.0 changes scale.
         */
        fun forRegistryModel(model: String?): DeviceFamily = when (model) {
            "4.0", "WHOOP 4.0" -> WHOOP4
            else -> WHOOP5
        }

        /** Whoop 5.0 CLIENT_HELLO bytes (16 bytes). Exposed as a named constant for test/debug use. */
        val WHOOP5_CLIENT_HELLO: ByteArray = byteArrayOf(
            0xAA.toByte(), 0x01, 0x08, 0x00, 0x00, 0x01, 0xE6.toByte(), 0x71,
            0x23, 0x01, 0x91.toByte(), 0x01, 0x36, 0x3E, 0x5C, 0x8D.toByte(),
        )
    }
}

/**
 * Whoop 5.0 "puffin" packet types mirror existing 4.0 types on the new transport. These map onto
 * the canonical base type names so they decode like their 4.0 counterparts instead of falling
 * through to an "unknown" label.
 */
object PuffinPacketType {
    /** Puffin command response — behaves like COMMAND_RESPONSE (type 36). */
    const val PUFFIN_COMMAND_RESPONSE: Int = 38

    /** Puffin metadata — behaves like METADATA (type 49). */
    const val PUFFIN_METADATA: Int = 56
}
