package com.noop.protocol

// WHOOP 5.0 / MG "R22" feature-flag config (deep-stream unlock) — direct port of the macOS/iOS
// `Whoop5Config` (Packages/WhoopProtocol/Sources/WhoopProtocol/Whoop5Config.swift).
//
// WHOOP 5/MG straps withhold their deep biometric streams (the high-rate "R22" optical/HR/motion
// packets, type 0x2F) from a freshly-connected client. The official app switches them on by writing
// a short burst of persistent feature-flag config values right after the hello handshake — a sequence
// independently documented by two third parties: judes.club's "Cracking the WHOOP 5 Bluetooth
// Protocol" (whose interactive frame-builder is the byte-level ground truth this is validated against)
// and Asherlc/dofek's docs/whoop-ble-protocol.md (Android APK decompilation), which corroborate the
// key names, values and the SET_FF_VALUE (0x78) opcode.
//
// Each flag is one SET_CONFIG (0x78) command whose 40-byte payload is the flag NAME as ASCII,
// NUL-padded to 32 bytes, then a one-byte value (itself an ASCII digit: '1'=0x31 or '2'=0x32) at
// offset 32, then 7 zero bytes. The inner b3 byte (0x01) is carried as the first payload byte ahead
// of the body, exactly like CLIENT_HELLO. Reversible (only changes which data the strap emits), gated
// behind an explicit opt-in, and writable only on real iOS/Android hardware. (#174)
object Whoop5Config {

    /** One persistent feature flag and the value the official app writes for it (ASCII '1'/'2'). */
    data class Flag(val name: String, val value: Int)

    /** The exact ordered enable sequence the official app sends (values ASCII '1'/'2').
     *  `enable_r22_packets` opens the type-0x2F biometric stream; the rest tune channel selection, wear
     *  detection and sleep behaviour. Flags 1–15 are transcribed verbatim from judes.club's frame-builder
     *  FLAGS array; flag 16 `enable_sig12` is NOT in that array — it was observed as a 16th SET_FF_VALUE
     *  write in a real on-strap iOS HCI capture (WHOOP 5.0, #103) that otherwise reproduced flags 1–15
     *  byte-for-byte in this order. `enable_sig12`'s value was corrected 0x32→0x31 (#423): a second real
     *  on-strap capture, this time spanning a live workout, reproduced flags 1–15 identically but decoded
     *  enable_sig12 as ASCII '1'. Keep in lockstep with the Swift `Whoop5Config.enableR22Sequence`. */
    val enableR22Sequence: List<Flag> = listOf(
        Flag("enable_r22_packets", 0x32),
        Flag("enable_r22_v2_packets", 0x32),
        Flag("enable_r22_v3_packets", 0x32),
        Flag("enable_r22_v4_packets", 0x31),
        Flag("enable_r22_v5_packets", 0x32),
        Flag("enable_r22_v6_packets", 0x32),
        Flag("enable_r22_v8_packets", 0x32),
        Flag("make_hrfm_visible", 0x32),
        Flag("disable_pip_r26_packets", 0x32),
        Flag("wear_detect_bias", 0x32),
        Flag("hr_ch_switching", 0x32),
        Flag("ir_hw_switching", 0x32),
        Flag("enable_passive_strap_fit_gen5", 0x31),
        Flag("enable_sig11_during_sleep", 0x32),
        Flag("dorset_inhibit_wpt", 0x32),
        Flag("enable_sig12", 0x31),   // #423: real on-strap capture during a live workout, corrected from 0x32
    )
}
