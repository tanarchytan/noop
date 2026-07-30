package com.noop.testcentre

/**
 * The domain tag stamped on each debug log line, and the profile id written into a bundle's meta.json.
 * UNIVERSAL is the preamble plus the three derived traces; MASTER is "log everything". Every remaining
 * domain has a live emitter, and the ids are stored values, so a rename changes what a bundle carries.
 */
enum class TestDomain(val id: String) {
    UNIVERSAL("universal"), SLEEP("sleep"), CONNECTION("connection"), WORKOUTS("workouts"),
    DISPLAY("display"), IMPORT("import"), STEPS("steps"),
    BATTERY("battery"), RECOVERY("recovery"), HRV("hrv"), MASTER("master")
}
