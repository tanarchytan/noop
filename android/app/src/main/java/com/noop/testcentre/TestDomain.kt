package com.noop.testcentre

/**
 * The domain tag stamped on each Test Centre log line and used to filter the export bundle.
 *
 * Was a byte-aligned twin of the Swift TestDomain (StrandAnalytics/TestDomain.swift). On the
 * Android-only noop-tan fork the four never-wired Phase-1 placeholders (notifications / sources /
 * stress / longevity) are dropped, so this id list is a deliberate, documented divergence from
 * upstream; every remaining domain has a live emitter. UNIVERSAL is the preamble plus the three
 * derived traces; MASTER is "log everything". Note IMPORT carries the wire id "import" (the Swift
 * dataImport case avoids the Swift reserved word; Kotlin uses IMPORT directly but keeps the same id).
 */
enum class TestDomain(val id: String) {
    UNIVERSAL("universal"), SLEEP("sleep"), CONNECTION("connection"), WORKOUTS("workouts"),
    DISPLAY("display"), IMPORT("import"), STEPS("steps"),
    BATTERY("battery"), RECOVERY("recovery"), HRV("hrv"), MASTER("master");

    /** GitHub label the deep-link self-applies, e.g. "test:sleep". MASTER becomes "test:all". */
    val githubLabel: String get() = if (this == MASTER) "test:all" else "test:$id"
}
