package com.noop.data

/**
 * Registry rows for the read-scope tests. [WhoopRepository.importedSourceIdsFor] takes the registry,
 * so every test that asserts a read scope builds its device list here rather than passing an id.
 */
internal fun deviceRow(
    id: String,
    status: DeviceStatus = DeviceStatus.paired,
    addedAt: Long = 0L,
    kind: SourceKind = SourceKind.liveBLE,
    included: Boolean = true,
): PairedDeviceRow = PairedDeviceRow(
    id = id,
    brand = "WHOOP",
    model = "5.0 MG",
    nickname = null,
    sourceKind = kind.name,
    capabilities = "hr,hrv,sleep",
    status = status.name,
    addedAt = addedAt,
    lastSeenAt = addedAt,
    dataIncluded = included,
)

/** The registry of a single-WHOOP install: the seeded legacy id, active. */
internal fun singleWhoopRegistry(): List<PairedDeviceRow> =
    listOf(deviceRow(WhoopRepository.WHOOP_SOURCE, DeviceStatus.active))

/** The registry after a remove-and-re-add: the legacy id retired, one live strap active. */
internal fun reAddedRegistry(activeId: String): List<PairedDeviceRow> = listOf(
    deviceRow(WhoopRepository.WHOOP_SOURCE, DeviceStatus.paired, addedAt = 0L),
    deviceRow(activeId, DeviceStatus.active, addedAt = 1L),
)
