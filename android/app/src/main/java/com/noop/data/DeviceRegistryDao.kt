package com.noop.data

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The device-registry slice of the DAO (pairedDevice / dayOwnership, schema v8). Split into its own
 * interface (which [WhoopDao] extends) so [DeviceRegistry] depends on a narrow, easily-faked surface
 * and can be unit-tested on the plain JVM without Room/Robolectric (see DeviceRegistryTest). Room
 * flattens these inherited annotated methods into the concrete @Dao at compile time, so they
 * generate identically to being declared on WhoopDao.
 */
interface DeviceRegistryDao {

    /** All paired devices, oldest first (ORDER BY addedAt ASC). */
    @Query("SELECT * FROM pairedDevice ORDER BY addedAt ASC")
    suspend fun pairedDevices(): List<PairedDeviceRow>

    /** Reactive twin of [pairedDevices]: re-emits when the registry changes, so a read scope derived
     *  from it follows a pair/archive/make-active without an app restart. */
    @Query("SELECT * FROM pairedDevice ORDER BY addedAt ASC")
    fun pairedDevicesFlow(): Flow<List<PairedDeviceRow>>

    /** The single `active` device id, or null if none (e.g. after archiving the only device). */
    @Query("SELECT id FROM pairedDevice WHERE status = 'active' LIMIT 1")
    suspend fun activeDeviceId(): String?

    /** Insert-or-replace a device by its id PK (ON CONFLICT(id) DO UPDATE upsert). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPairedDevice(row: PairedDeviceRow)

    /** Demote whatever device is currently active to `paired`. Half of the single-active swap
     *  (invariant I1); MUST run in the same transaction as [promote] — see [DeviceRegistry.setActive]. */
    @Query("UPDATE pairedDevice SET status = 'paired' WHERE status = 'active'")
    suspend fun demoteActive()

    /** Promote one device to `active` and stamp its lastSeenAt. Other half of the I1 swap. */
    @Query("UPDATE pairedDevice SET status = 'active', lastSeenAt = :now WHERE id = :id")
    suspend fun promote(id: String, now: Long)

    /** Archive a device (keeps the row + its samples — invariant I4). */
    @Query("UPDATE pairedDevice SET status = 'archived' WHERE id = :id")
    suspend fun archiveDevice(id: String)

    /** Permanently delete a device's registry row. The full-delete op clears its samples first. */
    @Query("DELETE FROM pairedDevice WHERE id = :id")
    suspend fun deletePairedDevice(id: String)

    /** Rename a device. A null/empty nickname clears it so the UI falls back to brand+model. */
    @Query("UPDATE pairedDevice SET nickname = :nickname WHERE id = :id")
    suspend fun renameDevice(id: String, nickname: String?)

    /** Persist (or clear) a device's stable BLE peripheral identifier (the MAC address on Android).
     *  Lets the seeded "my-whoop" adopt its strap's address on first connect, and a specific WHOOP
     *  confirm its identity. */
    @Query("UPDATE pairedDevice SET peripheralId = :peripheralId WHERE id = :id")
    suspend fun setPeripheralId(id: String, peripheralId: String?)

    /** The paired device whose [peripheralId] matches, or null if none — so a strap discovered by its
     *  MAC address can be resolved back to its registry row. */
    @Query("SELECT * FROM pairedDevice WHERE peripheralId = :peripheralId LIMIT 1")
    suspend fun deviceForPeripheralId(peripheralId: String): PairedDeviceRow?

    // MARK: deleteAllData — clear one device's recordings across every deviceId-keyed table.
    //
    // Room has no dynamic table names, so each device-scoped table gets its own DELETE here; the
    // orchestrator [DeviceRegistry.deleteDeviceData] runs them in one transaction (all-or-nothing).
    // The `pairedDevice` row itself is NOT deleted here — that is a separate op (invariant I4).

    @Query("DELETE FROM hrSample WHERE deviceId = :deviceId") suspend fun deleteHrFor(deviceId: String)
    @Query("DELETE FROM rrInterval WHERE deviceId = :deviceId") suspend fun deleteRrFor(deviceId: String)
    @Query("DELETE FROM spo2Sample WHERE deviceId = :deviceId") suspend fun deleteSpo2For(deviceId: String)
    @Query("DELETE FROM spo2PctSample WHERE deviceId = :deviceId") suspend fun deleteSpo2PctFor(deviceId: String)
    @Query("DELETE FROM skinTempSample WHERE deviceId = :deviceId") suspend fun deleteSkinTempFor(deviceId: String)
    @Query("DELETE FROM respSample WHERE deviceId = :deviceId") suspend fun deleteRespFor(deviceId: String)
    @Query("DELETE FROM gravitySample WHERE deviceId = :deviceId") suspend fun deleteGravityFor(deviceId: String)
    @Query("DELETE FROM stepSample WHERE deviceId = :deviceId") suspend fun deleteStepsFor(deviceId: String)
    @Query("DELETE FROM ppgHrSample WHERE deviceId = :deviceId") suspend fun deletePpgHrFor(deviceId: String)
    @Query("DELETE FROM ppgWaveformSample WHERE deviceId = :deviceId") suspend fun deletePpgWaveformFor(deviceId: String)
    @Query("DELETE FROM event WHERE deviceId = :deviceId") suspend fun deleteEventsFor(deviceId: String)
    @Query("DELETE FROM battery WHERE deviceId = :deviceId") suspend fun deleteBatteryFor(deviceId: String)
    @Query("DELETE FROM dailyMetric WHERE deviceId = :deviceId") suspend fun deleteDailyMetricsFor(deviceId: String)
    @Query("DELETE FROM sleepSession WHERE deviceId = :deviceId") suspend fun deleteSleepSessionsFor(deviceId: String)
    @Query("DELETE FROM journal WHERE deviceId = :deviceId") suspend fun deleteJournalFor(deviceId: String)
    @Query("DELETE FROM workout WHERE deviceId = :deviceId") suspend fun deleteWorkoutsFor(deviceId: String)
    @Query("DELETE FROM appleDaily WHERE deviceId = :deviceId") suspend fun deleteAppleDailyFor(deviceId: String)
    @Query("DELETE FROM metricSeries WHERE deviceId = :deviceId") suspend fun deleteMetricSeriesFor(deviceId: String)
    @Query("DELETE FROM dayOwnership WHERE deviceId = :deviceId") suspend fun deleteDayOwnershipFor(deviceId: String)
    // Device-keyed too: omitting these from delete-all would leave sleep-state, lab markers, live
    // coaching sessions, and dismissed workout/sleep markers behind — a privacy defect for a
    // delete-means-gone app. DeviceRegistryTest asserts every delete*For method is wired in.
    @Query("DELETE FROM sleepStateSample WHERE deviceId = :deviceId") suspend fun deleteSleepStatesFor(deviceId: String)
    @Query("DELETE FROM labMarker WHERE deviceId = :deviceId") suspend fun deleteLabMarkersFor(deviceId: String)
    @Query("DELETE FROM liveSession WHERE deviceId = :deviceId") suspend fun deleteLiveSessionsFor(deviceId: String)
    @Query("DELETE FROM dismissedWorkout WHERE deviceId = :deviceId") suspend fun deleteDismissedWorkoutsFor(deviceId: String)
    @Query("DELETE FROM dismissedSleep WHERE deviceId = :deviceId") suspend fun deleteDismissedSleepsFor(deviceId: String)

    /** Set the owner override for a day (insert-or-replace by the day PK). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setDayOwner(row: DayOwnershipRow)

    /** The owner override for a day, or null if none has been set. */
    @Query("SELECT * FROM dayOwnership WHERE day = :day")
    suspend fun dayOwner(day: String): DayOwnershipRow?
}
