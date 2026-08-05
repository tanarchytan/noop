package com.noop.ble

import android.content.Context
import android.util.Log
import com.noop.data.DeviceRegistry
import com.noop.data.PairedDeviceRow
import com.noop.data.SourceKind
import com.noop.data.StreamBatch
import com.noop.data.WhoopRepository
import com.noop.oura.OuraRingGen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs exactly ONE device's live BLE at a time, driven by [DeviceRegistry]'s active device id.
 *
 * A no-op whenever the active device is WHOOP (id "my-whoop", brand "WHOOP", or an unknown id) — the
 * existing WHOOP flow ([WhoopBleClient.connect] via [AppViewModel.connect]) runs untouched. Only acts
 * for a non-WHOOP Oura ring:
 *
 *   • switching TO one → [stopWhoop] (WHOOP's existing disconnect), then start the isolated
 *     [OuraLiveSource] for that ring's deviceId.
 *   • switching BACK to WHOOP → stop the [OuraLiveSource], then [startWhoop] (WHOOP's existing scan
 *     entry) — but only if we were actually on a ring, so a plain launch with WHOOP active does NOT
 *     re-trigger a redundant WHOOP scan.
 *
 * WHOOP start/stop are injected closures from the composition root, so the two BLE flows stay fully
 * decoupled. Live HR from a ring is pushed through [liveSink]; the app wires that to the SAME live
 * state the UI observes (e.g. `ble::publishExternalLiveHr`).
 *
 * The registry exposes the active id as a one-shot suspend read, so the app calls
 * [onActiveDeviceChanged] after any registry mutation that can change the active device, and [start]
 * reconciles once against the current active id at launch (a no-op for a single-WHOOP install).
 */
class SourceCoordinator(
    /** Android [Context] for the strap source's own scanner/GATT. Non-null in production; nullable
     *  only so registry-driven paths (e.g. identity adoption) are exercisable on the plain JVM
     *  without Android. */
    private val context: Context?,
    private val registry: DeviceRegistry,
    /** The store the strap source persists into. Non-null in production; nullable for the same
     *  JVM-test reason as [context]. */
    private val repository: WhoopRepository?,
    /** Push a strap's live HR/R-R into whatever the UI observes (e.g. `ble::publishExternalLiveHr`). */
    private val liveSink: (hr: Int, rr: List<Int>) -> Unit,
    /** Re-trigger WHOOP's EXISTING scan/connect entry point (e.g. `AppViewModel.connect`). */
    private val startWhoop: () -> Unit,
    /** Pause WHOOP via its EXISTING teardown (e.g. `AppViewModel.disconnect` → `ble.disconnect`). */
    private val stopWhoop: () -> Unit,
    /**
     * Pin the WHOOP connection to ONE strap by its persisted `peripheralId` (the MAC address), or
     * null to clear the pin back to "connect to the first WHOOP found". Wraps
     * [WhoopBleClient.preferredAddress]; called only on a WHOOP transition, null on the legacy
     * "my-whoop" path. Default no-op keeps existing call sites compiling.
     */
    private val setWhoopPreferredAddress: (String?) -> Unit = {},
    /**
     * Re-point which device id live WHOOP samples store under. Wraps
     * [WhoopBleClient.setActiveDeviceId]. Called only when the active WHOOP is not the seeded
     * "my-whoop" — the single-WHOOP path never invokes it. Default no-op keeps existing call sites
     * compiling.
     */
    private val setWhoopActiveDeviceId: (String) -> Unit = {},
    /** The WHOOP family the link is on, for the registry `model` label a lazily-created strap row
     *  carries. Wired to the persisted family at the composition root; defaults to the 5 series, the
     *  same fallback [com.noop.protocol.DeviceFamily.forRegistryModel] applies to an unknown label. */
    private val whoopFamily: () -> WhoopModel = { WhoopModel.WHOOP5_MG },
    /** Background scope for the suspend registry reads + persist. SupervisorJob keeps one failure from
     *  cancelling the others; IO keeps DB work off the main thread. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** Diagnostic sink for the multi-WHOOP identity-adoption "different strap connected" notice.
     *  Defaults to logcat; tests inject a capturing closure to assert the wording. Inert on the
     *  single-WHOOP path (the message only fires on a registered-but-mismatched strap). */
    private val log: (String) -> Unit = { Log.i("SourceCoordinator", it) },
    /** Diagnostic sink for the isolated Oura source's connect/auth/stream lifecycle. Wired at the
     *  composition root to [WhoopBleClient.externalLog] so those lines land in the same exported
     *  strap log. Kept separate from [log] above, which only carries the multi-WHOOP adoption
     *  notice. Default no-op keeps existing call sites compiling. */
    private val straplog: (String) -> Unit = {},
    /** Push an Oura ring's battery percent into the live state (e.g. `ble::publishExternalBattery`),
     *  so it surfaces where the WHOOP strap battery does. Default no-op keeps existing call sites
     *  and JVM tests compiling. */
    private val batterySink: (Int) -> Unit = {},
) {

    /** The active Oura source's live adopt outcome, mirrored so the Add-Oura wizard can leave its
     *  Adopting step (success -> close, failed -> the honest Failed step). [AdoptPhase.Idle] whenever
     *  no Oura source is live, so a stale outcome never drives a wizard transition. */
    private val _ouraAdoptPhase = MutableStateFlow(OuraLiveSource.AdoptPhase.Idle)
    val ouraAdoptPhase: StateFlow<OuraLiveSource.AdoptPhase> = _ouraAdoptPhase.asStateFlow()

    /** The active Oura source's honest needs-pairing message (null when none). The wizard treats a
     *  non-null value during Adopting as failure too (covers no-ack / ack != OK paths). null whenever
     *  no Oura source is live. */
    private val _ouraNeedsPairing = MutableStateFlow<String?>(null)
    val ouraNeedsPairing: StateFlow<String?> = _ouraNeedsPairing.asStateFlow()

    /** Collects the active Oura source's adoptPhase / needsPairing into the mirrors above; cancelled and
     *  nulled on teardown so a forgotten ring never leaks a stale outcome. */
    private var ouraStateJob: kotlinx.coroutines.Job? = null

    /** The single non-WHOOP source currently live — an Oura ring — held behind the [LiveHrSource]
     *  interface. null while WHOOP is active or nothing else is paired; exactly one non-WHOOP source
     *  is ever live at a time. Built by [makeSource]; owns its own scanner/GATT and never touches the
     *  WHOOP BLE client, so the WHOOP path cannot regress. */
    private var activeSource: LiveHrSource? = null
    /** The deviceId the active non-WHOOP source ([activeSource]) runs for. */
    private var activeStrapId: String? = null
    /** The WHOOP registry id we last pointed the connection at, so a WHOOP→WHOOP switch is detected
     *  and a repeat activation of the same WHOOP is a no-op. null until the first WHOOP activation. */
    private var activeWhoopId: String? = null
    /** True once we've transitioned onto a generic strap. While false (the default / WHOOP-active state)
     *  switching to WHOOP is a pure no-op — we never issue a redundant WHOOP (re)scan. */
    private var onStrap = false
    /** The last active id we reconciled, so a repeated [onActiveDeviceChanged] for the same id is a
     *  no-op. */
    private var lastSeenId: String? = null
    /** The address of the strap the WHOOP link is currently connected to, learned from
     *  [connectedPeripheralChanged]. Lets a WHOOP→WHOOP make-active adopt in place when the
     *  newly-activated row is the same physical strap — a stop/start churn there would drop the live
     *  link and force a scan reconnect. Cleared on disconnect (null address). */
    private var connectedWhoopAddress: String? = null

    /** Serializes [reconcile] so two device switches — or [start] racing [onActiveDeviceChanged] —
     *  can't interleave on the multi-threaded [scope] (Dispatchers.IO is a pool) and leak a
     *  half-torn-down live source. Persist launches stay outside this lock (they touch no
     *  coordinator state). */
    private val reconcileLock = Mutex()

    /**
     * Reconcile once against the CURRENT active id (launch). For a single-WHOOP install this resolves to
     * the WHOOP and is a pure no-op, so the existing WHOOP startup is untouched.
     */
    fun start() {
        scope.launch {
            val id = registry.activeDeviceId() ?: WhoopBleClient.DEFAULT_DEVICE_ID
            reconcileLock.withLock { reconcile(id) }
        }
    }

    /**
     * Called by the app after a registry mutation that can change the active device (the Devices
     * screen's setActive). Resolves the device for [id] and reconciles which live source runs.
     * Idempotent: a repeated call for the same id is dropped.
     */
    fun onActiveDeviceChanged(id: String) {
        scope.launch { reconcileLock.withLock { reconcile(id) } }
    }

    /**
     * The BLE engine connected to a WHOOP strap at [address] (null on disconnect). Persist that
     * stable identity onto the currently active device when it's a WHOOP and hasn't adopted one yet
     * — so a freshly-paired WHOOP confirms its identity — and MINT the dataset when the registry
     * holds no device yet, which is how a fresh install gets its first one.
     *
     * Guards (so this never corrupts the registry):
     *   • null address (a disconnect/never-connected republish) → ignore.
     *   • the active device is not a WHOOP (a generic strap is active) → ignore, this connection isn't ours.
     *   • the active WHOOP already has a different non-null peripheralId → a different strap
     *     connected; log it and do not clobber the stored identity (would mis-map another strap's
     *     samples onto this row).
     *   • it already matches → nothing to write.
     */
    fun connectedPeripheralChanged(address: String?) {
        // Track the live strap's address for the WHOOP->WHOOP adopt-in-place skip. A null address is
        // a disconnect/never-connected republish: clear it so a later make-active can't wrongly match
        // a stale link, then fall through to the existing ignore.
        connectedWhoopAddress = address
        if (address == null) return
        scope.launch {
            val activeId = registry.activeDeviceId() ?: adoptFirstStrap(address) ?: return@launch
            val devices = registry.all()
            val row = devices.firstOrNull { it.id == activeId }
            if (!isWhoop(activeId, devices) || row == null) return@launch

            val existing = row.peripheralId
            when {
                existing == null ->
                    // First connect for this WHOOP row → adopt the strap's stable identity (its address).
                    registry.setPeripheralId(activeId, address)
                existing.equals(address, ignoreCase = true) -> {
                    // Already adopted this exact strap → nothing to do.
                }
                else ->
                    // A DIFFERENT strap connected under this WHOOP row. Never silently overwrite — that would
                    // mis-map another physical strap's samples onto this device. Log and leave the stored id.
                    log(
                        "Multi-WHOOP: active device $activeId is registered to strap $existing but " +
                            "$address connected — not overwriting.",
                    )
            }
        }
    }

    /**
     * The connected strap reported its own serial (GATT 0x2A25). Bind it to a registry row
     * ([DeviceRegistry.bindSerial]): a band back on a NEW address lands on the row that already holds
     * its history, and a row pinned to this address that has no serial yet records one. A REUSE also
     * re-points the write id, since that row IS the strap on the link. Ignored with no address (the
     * serial always follows a connect) or on a non-WHOOP active source.
     */
    fun connectedSerialChanged(serial: String?) {
        val address = connectedWhoopAddress
        if (serial.isNullOrBlank() || address == null) return
        scope.launch {
            val devices = registry.all()
            val activeId = registry.activeDeviceId()
            if (activeId != null && !isWhoop(activeId, devices)) return@launch
            val reused = registry.bindSerial(serial, address) ?: return@launch
            setWhoopActiveDeviceId(reused)
            activeWhoopId = reused
            lastSeenId = reused
            log("Strap serial $serial identifies $reused — reusing its dataset at $address.")
        }
    }

    /**
     * The connected strap reported its hardware revision (GATT 0x2A27). Record it verbatim on the
     * active WHOOP row ([DeviceRegistry.recordHardwareRev]) so the board a capture came off stays
     * known after the fact. Ignored while the active source is not a WHOOP, and never guessed: a strap
     * that did not answer the read keeps its null.
     */
    fun connectedHardwareRevChanged(hardwareRev: String?) {
        if (hardwareRev.isNullOrBlank()) return
        scope.launch {
            val devices = registry.all()
            val activeId = registry.activeDeviceId() ?: return@launch
            if (!isWhoop(activeId, devices)) return@launch
            if (devices.firstOrNull { it.id == activeId }?.hardwareRev == hardwareRev) return@launch
            registry.recordHardwareRev(activeId, hardwareRev)
            log("Strap hardware revision $hardwareRev recorded on $activeId.")
        }
    }

    /**
     * Lazy creation: the registry names no active device and a WHOOP is on the link, so this is the
     * first strap this install has met — mint its row via [DeviceRegistry.adoptStrap] and point the
     * write id at it. Returns the new id, or null when some other device kind already holds the
     * registry (a ring), in which case this connection is not ours to adopt.
     */
    private suspend fun adoptFirstStrap(address: String): String? {
        val existing = registry.all()
        if (existing.any { com.noop.data.isDeviceRow(it) }) return null
        val id = registry.adoptStrap(address, model = whoopModelLabel())
        setWhoopActiveDeviceId(id)
        activeWhoopId = id
        lastSeenId = id
        log("First strap connected at $address — created its dataset as $id.")
        return id
    }

    /** The registry `model` label for a freshly-adopted strap. Slashed for the 5 series because this
     *  path never reads the GATT hardware-revision string whoop-rs classifies the variant from,
     *  matching what the Add wizard writes. */
    private fun whoopModelLabel(): String =
        if (whoopFamily() == WhoopModel.WHOOP4) "4.0" else "5.0 / MG"

    private suspend fun reconcile(id: String) {
        if (id == lastSeenId) return
        lastSeenId = id
        val devices = registry.all()
        // Contain every device-switch failure here. reconcile is the single entry point for both
        // start() and onActiveDeviceChanged(), running inside a bare `scope.launch {}` — a
        // SupervisorJob does NOT stop an uncaught throw from killing the process, and since the
        // active id is persisted, an uncaught throw here would crash-loop on every launch. Log the
        // exception into the exportable strap log too, and reset lastSeenId so the user can retry.
        try {
            if (isWhoop(id, devices)) switchToWhoop(id, devices) else switchToStrap(id, devices)
        } catch (t: Throwable) {
            lastSeenId = null
            log("SourceCoordinator: device switch to '$id' failed: ${t.javaClass.simpleName}: ${t.message}")
            straplog("HR-strap: activating this device failed (${t.javaClass.simpleName}: ${t.message}) - " +
                "staying on the previous source. Please share this log so we can fix it.")
        }
    }

    /**
     * Active device is a WHOOP ([id]). Three churn-guarded sub-cases:
     *   • Already streaming this exact WHOOP with no strap in between → pure no-op (the dormant
     *     default; the single-WHOOP launch lands here and touches nothing but the initial
     *     preferred-address).
     *   • Coming back from a generic strap → stop that source, point WHOOP at this id, resume its scan.
     *   • A different WHOOP → drop the current link, re-point (preferred address + deviceId), reconnect.
     */
    private fun switchToWhoop(id: String, devices: List<PairedDeviceRow>) {
        // Already streaming this exact WHOOP with no strap in between → nothing to do.
        if (!onStrap && activeWhoopId == id) return

        val peripheralId = devices.firstOrNull { it.id == id }?.peripheralId

        when {
            onStrap -> {
                // Coming back from a non-WHOOP source (an Oura ring): tear that source down first, then resume.
                tearDownNonWhoopSource()
                activeStrapId = null
                onStrap = false
                pointWhoop(id, peripheralId)
                startWhoop()
            }
            activeWhoopId == null -> {
                // First WHOOP activation of the session (the normal launch path). Sets the targeting
                // so the existing WHOOP flow uses it. For the seeded "my-whoop" (peripheralId null)
                // this is setWhoopPreferredAddress(null) with no setActiveDeviceId / scan / disconnect.
                pointWhoop(id, peripheralId)
            }
            peripheralId != null && peripheralId.equals(connectedWhoopAddress, ignoreCase = true) -> {
                // WHOOP → the same physical strap (make-active on the row already connected, e.g. the
                // pick-same-strap Add flow): adopt in place. A stop/start churn here would drop the
                // live link and force a scan reconnect (wrong-family default + OS-bond status=133).
                // Just re-point the targeting; the connection is untouched.
                pointWhoop(id, peripheralId)
            }
            else -> {
                // WHOOP → a DIFFERENT WHOOP: drop the current link, re-point, and reconnect.
                stopWhoop()
                pointWhoop(id, peripheralId)
                startWhoop()
            }
        }
    }

    /**
     * Apply the WHOOP targeting for the now-active WHOOP [id]. Always sets the preferred address
     * (null for the legacy "my-whoop" → connect to any WHOOP). Re-points the sample deviceId only for
     * a non-legacy WHOOP, since the seeded "my-whoop" keeps the bootstrap-set id. Records
     * [activeWhoopId] for future change detection.
     */
    private fun pointWhoop(id: String, peripheralId: String?) {
        setWhoopPreferredAddress(peripheralId)
        if (id != WhoopBleClient.DEFAULT_DEVICE_ID) {
            setWhoopActiveDeviceId(id)
        }
        activeWhoopId = id
    }

    /**
     * Active device is a non-WHOOP live source (an Oura ring). Pause WHOOP (once, on the WHOOP→source edge)
     * and run the isolated source for this device's id. Re-running for the SAME id is a no-op.
     */
    private fun switchToStrap(id: String, devices: List<PairedDeviceRow>) {
        if (activeStrapId == id) return   // already streaming this source → no churn

        val row = devices.firstOrNull { it.id == id }

        // Guard before touching the current link: the only non-WHOOP live source is the Oura ring.
        // Any other registered kind (an imported "Workout files"/GPX device, or a legacy non-Oura
        // strap row) has no live BLE stream — bail as a harmless no-op here. Pausing/tearing down
        // first and only then discovering nothing to build would strand the live link with no source
        // until a manual reconnect; activating an import row must never brick WHOOP.
        if (row?.sourceKind != SourceKind.oura.name) {
            log("SourceCoordinator: device '$id' (kind=${row?.sourceKind}) has no live BLE source; " +
                "only WHOOP and Oura stream live. Leaving the current source active.")
            return
        }

        if (!onStrap) stopWhoop()         // leaving WHOOP for the first non-WHOOP source → pause its BLE
        tearDownNonWhoopSource()          // source→source: stop the previous source first

        val address = row.peripheralId

        // Build the isolated Oura source (the one place mapping a kind to a concrete driver), then
        // bring it up. Adding a brand adds one arm in [makeSource] plus a conforming source.
        val source = makeSource(id, row)
        // Connect to the active strap's known BLE address rather than scan. A bare scan discovers and
        // lists the strap but never connects it, so it shows as "found" yet never streams.
        // connect(address) connects directly via getRemoteDevice; a bare scan is the fallback only
        // when the registry row has no address.
        if (!address.isNullOrEmpty()) source.connect(address) else source.scan()
        activeSource = source
        activeStrapId = id
        onStrap = true
    }

    /**
     * Build the isolated [LiveHrSource] for a device from its registered `sourceKind` — the one place
     * that maps a kind to a concrete driver. The only non-WHOOP live source NOOP supports is the
     * experimental Oura ring; every other device kind (imports) has no live BLE stream, so this fails
     * loudly for them (caught by [reconcile]'s guard). Returns the source without connecting — the
     * caller ([switchToStrap]) does the connect-by-address-else-scan bring-up.
     */
    private fun makeSource(id: String, row: PairedDeviceRow?): LiveHrSource {
        // Non-null in production; only JVM-test paths that never reach a source switch leave it null.
        // Fail loudly rather than silently no-op if that invariant breaks.
        val ctx = requireNotNull(context) { "SourceCoordinator.context is required to run a live source" }
        return when (row?.sourceKind) {
            SourceKind.oura.name -> makeOuraSource(id, ctx, row)
            else -> error(
                "SourceCoordinator: no live BLE source for device '$id' (kind=${row?.sourceKind}); " +
                    "only WHOOP and Oura stream live.",
            )
        }
    }

    /**
     * Build the experimental Oura ring source (gen3 / gen4 / gen5) for [id]. Also wires the
     * adopt-outcome mirror ([ouraStateJob]) and consumes the one-shot adopt consent — side effects
     * the coordinator owns, so they live here rather than in the plain [makeSource] dispatch.
     */
    private fun makeOuraSource(id: String, ctx: Context, row: PairedDeviceRow?): OuraLiveSource {
        val repo = requireNotNull(repository) { "SourceCoordinator.repository is required to persist Oura samples" }
        // Ring generation is carried on the row's model ("Oura Ring 3/4/5"); recovering it lets the
        // transport clamp the MTU and pick the gen-appropriate live-HR enable command set. Defaults to
        // gen3 if the model is missing/unrecognised.
        val ringGen = OuraRingGen.from(row?.model ?: "")
        val source = OuraLiveSource(
            context = ctx,
            deviceId = id,
            ringGen = ringGen,
            liveSink = { hr, rr -> liveSink(hr, rr) },   // ring HR + R-R → the existing live recorder
            // 16-byte application install key, read from the at-rest-encrypted key store keyed by this
            // ring's device id. Injected, never hardcoded; null drives the honest needs-pairing path
            // (no faked data). Read fresh on each connect so a key provisioned mid-session is picked
            // up on the post-install re-auth.
            authKey = { OuraInstallKeyStore.load(ctx, id) },
            persist = { batch: StreamBatch, deviceId: String ->
                scope.launch { runCatching { repo.insert(batch, deviceId) } }
            },
            log = straplog,           // Oura connect/auth/stream lifecycle → the same exported strap log
            onBattery = batterySink,  // ring battery → the same live state the WHOOP strap battery uses
        )
        // Consume the one-shot adopt-intent the wizard armed after its irreversible-consent gate and
        // second "Take over" confirm. True permits the post-factory-reset key install for this session
        // only; every later read-only reconnect reads false, so it never re-provisions a key.
        // setAdoptIntent must run before connect — the driver is built per connect with
        // allowKeyInstall wired from it — and the caller connects only after this returns.
        if (OuraInstallKeyStore.consumePendingAdopt(ctx, id)) {
            source.setAdoptIntent(true)
            straplog("Oura: adopt consent granted - this session may install NOOP's key")
        }
        // Mirror this source's live adopt outcome and needs-pairing message so the wizard can leave
        // its Adopting step on confirmed streaming or an honest Failed. Reset on teardown.
        ouraStateJob?.cancel()
        ouraStateJob = scope.launch {
            launch { source.adoptPhase.collect { _ouraAdoptPhase.value = it } }
            launch { source.needsPairing.collect { _ouraNeedsPairing.value = it } }
        }
        return source
    }

    /** Stop the live non-WHOOP source (an Oura ring) and drop the reference. Idempotent — exactly one
     *  source is ever live. */
    private fun tearDownNonWhoopSource() {
        activeSource?.stop()
        activeSource = null
        // Stop mirroring the (now torn-down) Oura source and clear the mirrors so a stale adopt outcome /
        // needs-pairing message never outlives the source or drives a later wizard transition.
        ouraStateJob?.cancel(); ouraStateJob = null
        _ouraAdoptPhase.value = OuraLiveSource.AdoptPhase.Idle
        _ouraNeedsPairing.value = null
    }

    companion object {
        /**
         * Classify a device id as WHOOP vs a generic strap. WHOOP if the id is the canonical
         * "my-whoop", the registry row's `brand` is "WHOOP" (case-insensitive), or the id is unknown —
         * unknown ids default to WHOOP so the coordinator stays dormant rather than stealing its BLE.
         */
        fun isWhoop(id: String, devices: List<PairedDeviceRow>): Boolean {
            if (id == WhoopBleClient.DEFAULT_DEVICE_ID) return true
            val device = devices.firstOrNull { it.id == id } ?: return true
            return isWhoop(device)
        }

        /** A device is WHOOP when its id is "my-whoop" or its brand is "WHOOP" (the seeded row's brand). */
        fun isWhoop(device: PairedDeviceRow): Boolean =
            device.id == WhoopBleClient.DEFAULT_DEVICE_ID ||
                device.brand.equals("WHOOP", ignoreCase = true)
    }
}
