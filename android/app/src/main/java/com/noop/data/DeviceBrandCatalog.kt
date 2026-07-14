package com.noop.data

import java.text.Normalizer

/**
 * Single source of truth for recognising a wearable BRAND from its advertised BLE name, and the stored
 * facts that follow from the brand. Pure (no android.bluetooth) so it's JVM-unit-tested — the recognition
 * + capability facts are byte-parity-critical and must be asserted headlessly on both platforms. Faithful
 * twin of Packages/WhoopStore/Sources/WhoopStore/DeviceBrandCatalog.swift.
 *
 * Adding a recognised brand is ONE row in [all]: the wizard's brand labelling ([brandGuess]), the
 * experimental-tier gate ([com.noop.ble.ExperimentalBrand]), and the source routing all derive from here
 * instead of re-listing the advertised-name tokens per call site.
 */
data class DeviceBrandSpec(
    /** Display + stored `PairedDeviceRow.brand` string ("Polar", "Amazfit", "Oura", …). */
    val brand: String,
    /** Lowercased, diacritic-folded advertised-name substrings that identify this brand. Checked in [all]
     *  order (most specific first) so a Huami sub-brand like Mi Band wins over the broader Amazfit tokens. */
    val nameTokens: List<String>,
    /** The routing kind stored on the device (drives `SourceCoordinator.makeSource`). [SourceKind.oura]
     *  for the experimental Oura ring source. */
    val sourceKind: SourceKind,
    /** The registry id prefix for a device of this brand ("oura"). */
    val idPrefix: String,
    /** Whether this brand can stream LIVE heart rate at all in NOOP. false for Oura (no open live stream) —
     *  the wizard routes those to a locally-adopted key path instead of pretending to connect. */
    val canStreamLiveHR: Boolean,
    /** True for the opt-in EXPERIMENTAL tier (Oura). `ExperimentalBrand.recognise` returns only these. */
    val isExperimentalTier: Boolean,
)

object DeviceBrandCatalog {
    /** The brand table. Only the experimental Oura ring is recognised here; WHOOP is detected by its own
     *  path (`SourceCoordinator.isWhoop`), not by advertised-name token. */
    val all: List<DeviceBrandSpec> = listOf(
        DeviceBrandSpec("Oura", listOf("oura"),
            SourceKind.oura, "oura", canStreamLiveHR = false, isExperimentalTier = true),
    )

    /** The brand whose advertised name matches, or null if unrecognised. Diacritic-folded (NFD + strip
     *  combining marks, mirroring Swift's `.diacriticInsensitive`) + lowercased; substring match in [all]
     *  order. Twin of Swift `DeviceBrandCatalog.spec(forAdvertisedName:)`. */
    fun specForAdvertisedName(name: String): DeviceBrandSpec? {
        val n = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        return all.firstOrNull { spec -> spec.nameTokens.any { n.contains(it) } }
    }

    /** The row for a known brand string ("Amazfit", …), or null. Lets the typed `ExperimentalBrand` derive
     *  its facts (capability/routing) from this table rather than re-declaring them. */
    fun specForBrand(brand: String): DeviceBrandSpec? = all.firstOrNull { it.brand == brand }
}
