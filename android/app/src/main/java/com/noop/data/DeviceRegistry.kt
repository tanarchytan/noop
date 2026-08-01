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

    /** The sources that are an actual device ([DEVICE_SOURCE_KINDS]). A file import or the
     *  pre-registry bucket names data, not hardware, so it never reaches a device list. */
    suspend fun devices(): List<PairedDeviceRow> = all().filter { it.sourceKind in DEVICE_SOURCE_KINDS }

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
     * Persist (or clear) a device's stable BLE peripheral identifier (the MAC address on Android).
     * Lets the pre-registry bucket adopt its strap's address on first connect, and a specific WHOOP
     * confirm its identity.
     *
     * Adoption is the moment a device is known to exist, so a `legacy` bucket becomes a `liveBLE`
     * device here rather than being listed as one on the chance that a strap shows up.
     */
    suspend fun setPeripheralId(id: String, peripheralId: String?) {
        transactor.run {
            dao.setPeripheralId(id, peripheralId)
            if (peripheralId == null) return@run
            // Narrowed to `legacy`, so this can never rewrite the kind of an import or a real device.
            val row = dao.pairedDevices().firstOrNull { it.id == id } ?: return@run
            if (row.sourceKind == SourceKind.legacy.name) {
                dao.upsertPairedDevice(row.copy(sourceKind = SourceKind.liveBLE.name, peripheralId = peripheralId))
            }
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
    }

    /** Set the owner override for a day (insert-or-replace). */
    suspend fun setDayOwner(day: String, deviceId: String, locked: Boolean) =
        dao.setDayOwner(DayOwnershipRow(day = day, deviceId = deviceId, locked = locked))

    /** The owner override for a day, or null if none. */
    suspend fun dayOwner(day: String): DayOwnershipRow? = dao.dayOwner(day)
}
