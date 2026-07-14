package com.noop.ble

import com.noop.data.DeviceBrandCatalog
import com.noop.data.SourceKind

/**
 * CLEAN-ROOM best-effort recognition of the EXPERIMENTAL band families from an advertised device name.
 *
 * A thin TYPED VIEW over [DeviceBrandCatalog] (the pure, JVM-unit-tested single source of truth in
 * com.noop.data): the advertised-name tokens and capability facts live there once, and this enum only
 * names the experimental family so the driver can switch on it. Deliberately conservative: an
 * unrecognised name returns null rather than a wrong guess. NOTHING here fabricates data — it only labels
 * a discovered peripheral so the experimental add-device flow can show the honest per-brand guidance.
 * US English throughout.
 */
enum class ExperimentalBrand(val displayBrand: String) {
    /** Oura ring. Locally-adopted, best-effort: NOOP owns the ring by key and reads its own raw signals +
     *  open event tags, computing its own scores; it surfaces an honest "needs pairing" state when the
     *  install key is absent. */
    OURA("Oura");

    /** Whether this brand can stream LIVE heart rate at all, derived from the catalog (false for Oura — no
     *  open live stream — so the wizard routes it to import). false if the catalog row is somehow missing. */
    val canStreamLiveHR: Boolean
        get() = DeviceBrandCatalog.specForBrand(displayBrand)?.canStreamLiveHR ?: false

    /** Routing kind stored on a device of this brand (from the catalog); liveBLE fallback (a standard-HR
     *  strap — never steals the WHOOP path). */
    val sourceKind: SourceKind
        get() = DeviceBrandCatalog.specForBrand(displayBrand)?.sourceKind ?: SourceKind.liveBLE

    /** Registry id prefix for a device of this brand (from the catalog); "strap" fallback. The device id
     *  (== sample deviceId) is "<idPrefix>-<address>", so this MUST stay byte-identical to the value the
     *  wizard previously hardcoded — a test pins each experimental brand's prefix. */
    val idPrefix: String
        get() = DeviceBrandCatalog.specForBrand(displayBrand)?.idPrefix ?: "strap"

    companion object {
        /** Best-effort brand from an advertised name. Returns null for an unrecognised name. All token
         *  matching lives in [DeviceBrandCatalog]. */
        fun recognise(name: String): ExperimentalBrand? {
            val spec = DeviceBrandCatalog.specForAdvertisedName(name) ?: return null
            if (!spec.isExperimentalTier) return null
            return values().firstOrNull { it.displayBrand == spec.brand }
        }
    }
}
