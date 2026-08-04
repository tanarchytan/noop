package com.noop.data

import androidx.room.withTransaction

/**
 * Device-registry façade over [WhoopDao] + [WhoopDatabase]. Owns the device list, the
 * single-active invariant, and the day-ownership override table.
 *
 * Invariant I1 (at most one `active` device) is enforced in [setActive]: the demote+promote pair
 * runs inside one transaction, so a crash mid-swap can never leave two active rows (or none).
 *
 * The transaction boundary is injected as [transactor] (defaulting to Room's `db.withTransaction`)
 * so the registry's logic is exercisable on the plain JVM without a real Room database.
 */
class DeviceRegistry(
    private val dao: DeviceRegistryDao,
    private val transactor: Transactor,
) {
    /** A single-transaction boundary. Production wraps Room's `withTransaction`; tests pass through.
     *  Not a `fun interface` — a SAM method may not be generic — so implementors use the object form. */
    interface Transactor {
        suspend fun <R> run(block: suspend () -> R): R
    }

    /** Production constructor: wraps the DAO + Room transaction over [db]. */
    constructor(db: WhoopDatabase) : this(
        dao = db.whoopDao(),
        transactor = object : Transactor {
            override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction { block() }
        },
    )

    /** Every registered source, oldest first — devices, imports and the legacy bucket alike. This is
     *  the provenance list; use [devices] for anything the user meets as a device. */
    suspend fun all(): List<PairedDeviceRow> = dao.pairedDevices()

    /** The sources that are an actual device ([isDeviceRow]). A file import, a service, or the WHOOP
     *  import sink names data, not hardware, so it never reaches a device list. */
    suspend fun devices(): List<PairedDeviceRow> = all().filter { isDeviceRow(it) }

    /** The single active device id, or null if none. */
    suspend fun activeDeviceId(): String? = dao.activeDeviceId()

    /** Add (or update) a device. */
    suspend fun add(row: PairedDeviceRow) = dao.upsertPairedDevice(row)

    /**
     * Make [id] the single active device. The demote-old + promote-new pair is ONE transaction, so
     * the "exactly one active" invariant (I1) holds even across a crash mid-swap.
     */
    suspend fun setActive(id: String, now: Long = System.currentTimeMillis() / 1000) {
        transactor.run {
            dao.demoteActive()
            dao.promote(id, now)
        }
    }

    /** Archive a device — the PRESENCE axis only. The row, its samples and its place in the read
     *  scope all survive, so "removed, data kept" is what the user gets (invariant I4). */
    suspend fun archive(id: String) = dao.archiveDevice(id)

    /** Include or exclude one dataset from every read. The INCLUSION axis: no sample row is touched,
     *  and BLE presence is unaffected. */
    suspend fun setDataIncluded(id: String, included: Boolean) = dao.setDataIncluded(id, included)

    /**
     * Persist (or clear) a device's stable BLE peripheral identifier (the MAC address on Android), so
     * a specific WHOOP confirms its identity on connect.
     *
     * Adoption is the moment a device is known to exist, so a `legacy` bucket becomes a `liveBLE`
     * device here rather than being listed as one on the chance that a strap shows up. The WHOOP
     * import sink is exempt: it is a pile of rows, and a strap gets its own row from [adoptStrap].
     */
    suspend fun setPeripheralId(id: String, peripheralId: String?) {
        transactor.run {
            dao.setPeripheralId(id, peripheralId)
            if (peripheralId == null || id == WhoopRepository.WHOOP_SOURCE) return@run
            // Narrowed to `legacy`, so this can never rewrite the kind of an import or a real device.
            val row = dao.pairedDevices().firstOrNull { it.id == id } ?: return@run
            if (row.sourceKind == SourceKind.legacy.name) {
                dao.upsertPairedDevice(row.copy(sourceKind = SourceKind.liveBLE.name, peripheralId = peripheralId))
            }
        }
    }

    /**
     * The strap at [peripheralId], creating its row the FIRST time one connects. A registry with no
     * device is the honest empty state a fresh install starts in, so the dataset is minted here rather
     * than seeded — and never for an address a row already pins, which would fork one strap in two.
     * Returns the row's id.
     */
    suspend fun adoptStrap(
        peripheralId: String,
        model: String,
        now: Long = System.currentTimeMillis() / 1000,
        serial: String? = null,
    ): String = transactor.run {
        serial?.let { s ->
            dao.pairedDevices().firstOrNull { it.serial?.equals(s, ignoreCase = true) == true }
                ?.let { return@run it.id }
        }
        dao.deviceForPeripheralId(peripheralId)?.let { return@run it.id }
        val id = "whoop-$peripheralId"
        dao.pairedDevices().firstOrNull { it.id == id }?.let { return@run it.id }
        dao.upsertPairedDevice(
            PairedDeviceRow(
                id = id,
                brand = "WHOOP",
                model = model,
                nickname = null,
                peripheralId = peripheralId,
                sourceKind = SourceKind.liveBLE.name,
                capabilities = "hr,hrv,spo2,skinTemp,sleep,strainLoad",
                status = DeviceStatus.paired.name,
                addedAt = now,
                lastSeenAt = now,
                serial = serial,
            ),
        )
        dao.demoteActive()
        dao.promote(id, now)
        id
    }

    /**
     * Bind a connected strap's own serial (GATT 0x2A25) to a registry row, per [StrapIdentity]:
     * re-point the row that already carries it (a band back on a new address), or record it on the row
     * this address already pins. Returns the row a REUSE landed on, so the caller can make it the write
     * target; null for every other outcome, which writes nothing but the serial.
     */
    suspend fun bindSerial(
        serial: String,
        address: String,
        now: Long = System.currentTimeMillis() / 1000,
    ): String? = transactor.run {
        when (val outcome = StrapIdentity.resolve(serial, address, dao.pairedDevices())) {
            is StrapIdentity.Outcome.Reuse -> {
                dao.setPeripheralId(outcome.id, address)
                dao.demoteActive()
                dao.promote(outcome.id, now)
                outcome.id
            }
            is StrapIdentity.Outcome.Backfill -> {
                dao.setSerial(outcome.id, serial)
                null
            }
            is StrapIdentity.Outcome.Ambiguous, StrapIdentity.Outcome.None -> null
        }
    }

    /** The paired device whose `peripheralId` matches [peripheralId], or null if none — resolves a
     *  strap discovered by its MAC address back to its registry row. */
    suspend fun deviceForPeripheralId(peripheralId: String): PairedDeviceRow? =
        dao.deviceForPeripheralId(peripheralId)

    /** Rename a device. A blank [nickname] clears it so the UI falls back to brand+model. Trims
     *  whitespace before checking. */
    suspend fun rename(id: String, nickname: String?) {
        val trimmed = nickname?.trim()
        dao.renameDevice(id, if (!trimmed.isNullOrEmpty()) trimmed else null)
    }

    /**
     * Permanently deletes every recorded sample/derived row for [id], across all deviceId-keyed
     * tables in [WhoopDatabase], in ONE transaction. The `pairedDevice` registry row is left intact
     * (I4); DeviceRegistryTest guards that every such table has a wired delete.
     */
    suspend fun deleteDeviceData(id: String) {
        transactor.run { deleteAllDataRows(id) }
    }

    /**
     * Permanently delete a device ENTIRELY: its registry row AND every recorded sample/derived row, in ONE
     * transaction. Unlike [deleteDeviceData] (which keeps the row per I4), this removes the entry so the
     * device leaves the list for good.
     */
    suspend fun delete(id: String) {
        transactor.run {
            deleteAllDataRows(id)
            dao.deletePairedDevice(id)
        }
    }

    /** Every device-keyed table delete, shared by [deleteDeviceData] + [delete]. Kept complete against
     *  the [WhoopDatabase] schema; DeviceRegistryTest guards that no delete*For method is missed. */
    private suspend fun deleteAllDataRows(id: String) {
        dao.deleteHrFor(id)
        dao.deleteRrFor(id)
        dao.deleteSpo2For(id)
        dao.deleteSpo2PctFor(id)
        dao.deleteSkinTempFor(id)
        dao.deleteRespFor(id)
        dao.deleteGravityFor(id)
        dao.deleteStepsFor(id)
        dao.deletePpgHrFor(id)
        dao.deletePpgWaveformFor(id)
        dao.deleteEventsFor(id)
        dao.deleteBatteryFor(id)
        dao.deleteDailyMetricsFor(id)
        dao.deleteSleepSessionsFor(id)
        dao.deleteJournalFor(id)
        dao.deleteWorkoutsFor(id)
        dao.deleteAppleDailyFor(id)
        dao.deleteMetricSeriesFor(id)
        dao.deleteDayOwnershipFor(id)
        dao.deleteSleepStatesFor(id)
        dao.deleteLabMarkersFor(id)
        dao.deleteLiveSessionsFor(id)
        dao.deleteDismissedWorkoutsFor(id)
        dao.deleteDismissedSleepsFor(id)
        dao.deleteV18For(id)
        dao.deleteImuFeaturesFor(id)
        dao.deleteRhythmScreensFor(id)
        // Morphology hangs off the capture, so it goes first; deleting ecgSession first would strand it.
        dao.deleteRhythmMorphologyFor(id)
        dao.deleteEcgSessionsFor(id)
    }

    /** Set the owner override for a day (insert-or-replace). */
    suspend fun setDayOwner(day: String, deviceId: String, locked: Boolean) =
        dao.setDayOwner(DayOwnershipRow(day = day, deviceId = deviceId, locked = locked))

    /** The owner override for a day, or null if none. */
    suspend fun dayOwner(day: String): DayOwnershipRow? = dao.dayOwner(day)
}
