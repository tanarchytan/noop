package com.noop.analytics

import com.noop.data.DeviceRegistry
import com.noop.data.DeviceStatus
import com.noop.data.SourceKind
import com.noop.protocol.DeviceFamily

/**
 * [IntelligenceEngine.DayOwnerSource] backed by the [DeviceRegistry]. Supplies the engine with
 * per-day owner-resolution inputs so a day is scored from exactly ONE device (invariant I2),
 * without giving the pure-JVM engine a Room dependency.
 *
 * Priority: 0 = the active strap, 1 = other live (BLE/historyBLE) straps, 2 = imports
 * (cloud/file), 3 = an activity file, 4 = the pre-registry bucket. Lower wins; archived devices are
 * excluded. The bucket ranks last because it names data with no known source, so it takes a day only
 * when nothing else covers it.
 */
class RegistryDayOwnerSource(private val registry: DeviceRegistry) : IntelligenceEngine.DayOwnerSource {

    override suspend fun candidatePriorities(): List<Pair<String, Int>> {
        val activeId = registry.activeDeviceId()
        return registry.all()
            .filter { it.status != DeviceStatus.archived.name }
            .map { d ->
                val isImport = d.sourceKind == SourceKind.cloudImport.name ||
                    d.sourceKind == SourceKind.fileImport.name
                // An activity-file ride ranks below whole-day imports (priority 3 vs 2), so a
                // full-day WHOOP CSV/cloud import keeps ownership of a day it has HR for; the
                // ride only wins a day nothing else covers.
                val priority = when {
                    d.id == activeId -> 0
                    d.sourceKind == SourceKind.legacy.name -> 4
                    d.sourceKind == SourceKind.activityFile.name -> 3
                    isImport -> 2
                    else -> 1
                }
                d.id to priority
            }
    }

    // Any dayOwnership override wins outright, regardless of its `locked` flag: it is treated
    // as an authoritative override (the `locked` flag gates the UI, not this read).
    override suspend fun lockedOwner(day: String): String? = registry.dayOwner(day)?.deviceId

    // CAPTURE-B: the registry's active strap id, for the universal dayOwner diagnostic's writeActiveId.
    // Matches the id the live read path resolves to, so the diagnostic line can prove the read
    // owner and the write target are the same device, or surface it when they diverge.
    override suspend fun activeWriteId(): String? = registry.activeDeviceId()

    // Resolve the strap family that wrote [deviceId]'s rows from its registry model. The
    // model-label → family mapping (and the WHOOP5 fallback for unknowns) lives in
    // DeviceFamily.forRegistryModel.
    override suspend fun skinTempFamily(deviceId: String): DeviceFamily {
        val model = registry.all().firstOrNull { it.id == deviceId }?.model
        return DeviceFamily.forRegistryModel(model)
    }
}
