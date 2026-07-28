package com.noop.ingest

import com.noop.protocol.DeviceFamily

/**
 * Which straps' blood oxygen may cross the app boundary, in either direction: ours leaving through
 * Health Connect, and Health Connect's filling a gap in ours. One rule for both, so the two sides
 * cannot drift apart.
 */
internal object Spo2Policy {

    /**
     * True only for 5.0/MG, whose percent the strap computes itself; the 4.0 figure is derived here
     * and unproven, so it neither leaves the device nor is filled from elsewhere. An absent label is
     * untrusted — the risk runs opposite [DeviceFamily.forRegistryModel], which defaults an
     * unrecognised label to 5.0 since only a positively-identified 4.0 changes its skin-temp scale.
     */
    fun trusted(model: String?): Boolean =
        model != null && DeviceFamily.forRegistryModel(model) != DeviceFamily.WHOOP4
}
