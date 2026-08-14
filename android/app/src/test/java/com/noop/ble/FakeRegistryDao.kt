package com.noop.ble

import com.noop.data.DayOwnershipRow
import com.noop.data.DeviceRegistryDao
import com.noop.data.DeviceStatus
import com.noop.data.PairedDeviceRow

/**
 * In-memory [DeviceRegistryDao] for the coordinator suites. Shared rather than duplicated: the
 * interface carries 45 methods, and two fakes would drift.
 */
internal class FakeRegistryDao : DeviceRegistryDao {
    val devices = LinkedHashMap<String, PairedDeviceRow>()
    val owners = LinkedHashMap<String, DayOwnershipRow>()

    override suspend fun pairedDevices(): List<PairedDeviceRow> = devices.values.sortedBy { it.addedAt }
    override fun pairedDevicesFlow(): kotlinx.coroutines.flow.Flow<List<PairedDeviceRow>> =
        kotlinx.coroutines.flow.flowOf(devices.values.sortedBy { it.addedAt })
    override suspend fun activeDeviceId(): String? =
        devices.values.firstOrNull { it.status == DeviceStatus.active.name }?.id
    override suspend fun upsertPairedDevice(row: PairedDeviceRow) { devices[row.id] = row }
    override suspend fun demoteActive() {
        for ((id, row) in devices) if (row.status == DeviceStatus.active.name) {
            devices[id] = row.copy(status = DeviceStatus.paired.name)
        }
    }
    override suspend fun promote(id: String, now: Long) {
        devices[id]?.let { devices[id] = it.copy(status = DeviceStatus.active.name, lastSeenAt = now) }
    }
    override suspend fun archiveDevice(id: String) {
        devices[id]?.let { devices[id] = it.copy(status = DeviceStatus.archived.name) }
    }
    override suspend fun setDataIncluded(id: String, included: Boolean) {
        devices[id]?.let { devices[id] = it.copy(dataIncluded = included) }
    }
    override suspend fun setSerial(id: String, serial: String?) {
        devices[id]?.let { devices[id] = it.copy(serial = serial) }
    }
    override suspend fun setHardwareRev(id: String, hardwareRev: String?) {
        devices[id]?.let { devices[id] = it.copy(hardwareRev = hardwareRev) }
    }
    override suspend fun deletePairedDevice(id: String) { devices.remove(id) }
    override suspend fun renameDevice(id: String, nickname: String?) {
        devices[id]?.let { devices[id] = it.copy(nickname = nickname) }
    }
    override suspend fun setPeripheralId(id: String, peripheralId: String?) {
        devices[id]?.let { devices[id] = it.copy(peripheralId = peripheralId) }
    }
    override suspend fun deviceForPeripheralId(peripheralId: String): PairedDeviceRow? =
        devices.values.firstOrNull { it.peripheralId == peripheralId }
    override suspend fun setDayOwner(row: DayOwnershipRow) { owners[row.day] = row }
    override suspend fun dayOwner(day: String): DayOwnershipRow? = owners[day]

    // Sample-table deletes are unmodelled here (validated by the Room-backed integration).
    override suspend fun deleteHrFor(deviceId: String) {}
    override suspend fun deleteRrFor(deviceId: String) {}
    override suspend fun deleteSpo2For(deviceId: String) {}
    override suspend fun deleteSpo2PctFor(deviceId: String) {}
    override suspend fun deleteSkinTempFor(deviceId: String) {}
    override suspend fun deleteRespFor(deviceId: String) {}
    override suspend fun deleteGravityFor(deviceId: String) {}
    override suspend fun deleteStepsFor(deviceId: String) {}
    override suspend fun deletePpgHrFor(deviceId: String) {}
    override suspend fun deletePpgWaveformFor(deviceId: String) {}
    override suspend fun deleteEventsFor(deviceId: String) {}
    override suspend fun deleteBatteryFor(deviceId: String) {}
    override suspend fun deleteDailyMetricsFor(deviceId: String) {}
    override suspend fun deleteSleepSessionsFor(deviceId: String) {}
    override suspend fun deleteJournalFor(deviceId: String) {}
    override suspend fun deleteWorkoutsFor(deviceId: String) {}
    override suspend fun deleteAppleDailyFor(deviceId: String) {}
    override suspend fun deleteMetricSeriesFor(deviceId: String) {}
    override suspend fun deleteSleepStatesFor(deviceId: String) {}
    override suspend fun deleteLabMarkersFor(deviceId: String) {}
    override suspend fun deleteLiveSessionsFor(deviceId: String) {}
    override suspend fun deleteDismissedWorkoutsFor(deviceId: String) {}
    override suspend fun deleteDismissedSleepsFor(deviceId: String) {}
    override suspend fun deleteV18For(deviceId: String) {}
    override suspend fun deleteImuFeaturesFor(deviceId: String) {}
    override suspend fun deleteRhythmScreensFor(deviceId: String) {}
    override suspend fun deleteRhythmMorphologyFor(deviceId: String) {}
    override suspend fun deleteEcgSessionsFor(deviceId: String) {}
    override suspend fun deleteDayOwnershipFor(deviceId: String) {
        owners.entries.removeIf { it.value.deviceId == deviceId }
    }
}
