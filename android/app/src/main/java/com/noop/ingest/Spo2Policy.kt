package com.noop.ingest

import com.noop.protocol.DeviceFamily

/**
 * Which straps' blood oxygen may cross the app boundary, in either direction: ours leaving through
 * Health Connect, and Health Connect's filling a gap in ours. One rule for both, so the two sides
 * cannot drift apart.
 */
internal object Spo2Policy {

    /**
     * True only for 5.0/MG, whose percent the strap computes itself. The 4.0 figure is derived here
     * and still under investigation, so it neither leaves the device nor gets filled from elsewhere.
     *
     * An ABSENT label is untrusted. [DeviceFamily.forRegistryModel] resolves an unknown label to 5.0
     * because only a positively-identified 4.0 changes its skin-temp scale; here the risk runs the
     * other way, so this needs a label it actually recognises.
     */
    fun trusted(model: String?): Boolean =
        model != null && DeviceFamily.forRegistryModel(model) != DeviceFamily.WHOOP4
}
