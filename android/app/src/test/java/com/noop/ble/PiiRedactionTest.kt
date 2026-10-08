package com.noop.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strap-log PII redaction ([redactStrapLogPii]).
 *
 * Regression guard for #421: the MAC scrubber regex has exactly two capture groups (first + last
 * octet), so the replacement must reference $1/$2. A stray `$3` made `replace()` throw
 * IndexOutOfBoundsException("No group 3") the instant a raw MAC was logged — which happened the
 * moment a generic-HR strap (Polar H10 etc.) was activated, since StandardHrSource logs
 * `device.address`. The thrown exception aborted the strap's activation, so the strap never streamed.
 */
class PiiRedactionTest {

    @Test fun masksMacKeepingFirstAndLastOctet() {
        // The exact line that triggered #421 (a generic-HR strap's address being logged).
        val out = redactStrapLogPii("HR-strap: connecting to A1:B2:C3:D4:E5:F6")
        assertEquals("HR-strap: connecting to A1:••:••:••:••:F6", out)
    }

    @Test fun doesNotThrowOnAnyMac() {
        // The whole bug was a thrown exception, not a wrong string — assert it completes.
        for (mac in listOf("00:11:22:33:44:55", "AA:bb:CC:dd:EE:ff", "de:ad:be:ef:12:34")) {
            val out = redactStrapLogPii("connecting to $mac now")
            assertFalse("middle octets must be masked: $out", out.contains(mac))
        }
    }

    @Test fun masksWhoopSerial() {
        assertEquals("Discovered WHOOP <serial> (rssi -63)",
            redactStrapLogPii("Discovered WHOOP 4C1594026 (rssi -63)"))
    }

    @Test fun leavesModelNamesAndPlainTextAlone() {
        // "WHOOP 4.0" is a dotted model name, not a serial — must not be scrubbed.
        assertEquals("Auto-reconnecting to your saved WHOOP 4.0…",
            redactStrapLogPii("Auto-reconnecting to your saved WHOOP 4.0…"))
        assertEquals("Backfill: session ended — reason=HISTORY_COMPLETE",
            redactStrapLogPii("Backfill: session ended — reason=HISTORY_COMPLETE"))
    }

    /**
     * Regression for #453: a WHOOP 5/MG reconnect logs a frame line containing a MAC; redaction must
     * mask it WITHOUT throwing. The $3 bug here crashed the whole app on every Bluetooth-on reconnect.
     */
    @Test fun frameLineWithMacIsRedactedNotCrashed() {
        val out = redactStrapLogPii("handleFrame from AA:BB:CC:DD:EE:FF — 24 bytes")
        assertEquals("handleFrame from AA:••:••:••:••:FF — 24 bytes", out)
    }

    /** Defense-in-depth (#453): redaction is TOTAL — it never throws, on any input, ever. */
    @Test fun neverThrowsOnAdversarialInput() {
        val nasty = listOf(
            "", "no pii here",
            "literal dollar \$3 and \${0} and \\1 in the text",
            "AA:BB:CC:DD:EE:FF WHOOP 4C1594026 mixed $ \\ ${'$'}{",
            "x".repeat(20000),
            "00:11:22:33:44:55 ".repeat(500),
        )
        for (s in nasty) {
            // The contract is "returns a String, never throws" — assert it completes for every input.
            val out = redactStrapLogPii(s)
            assertTrue("must return a value, got null", out.isNotEmpty() || s.isEmpty())
        }
    }

    /**
     * The name never reaches a shared log at all. The sink rule below is defence-in-depth; this is the
     * real guarantee, and it is an ALLOWLIST so an unanticipated naming shape is dropped by default.
     */
    @Test fun logSafeDeviceNameKeepsOnlyTheModel() {
        assertEquals("<name> Whoop", logSafeDeviceName("Ryan's Whoop"))
        // #2337: the model token FIRST, which every other case here has last. Second-hand straps arrive
        // named after the previous owner in whatever word order that language uses, and a real report
        // arrived as exactly this shape. Synthetic given name on purpose: this file is public.
        assertEquals("<name> Whoop", logSafeDeviceName("Whoop von Beispiel"))
        assertEquals("<name> WHOOP 4.0", logSafeDeviceName("Ryan B's WHOOP 4.0"))
        assertEquals("<name> WHOOP 5.0 MG", logSafeDeviceName("Ryan\u2019s WHOOP 5.0 MG"))
    }

    /** A fully custom name has no model token to keep, so nothing of it survives. */
    @Test fun logSafeDeviceNameDropsAWhollyCustomName() {
        assertEquals("<name>", logSafeDeviceName("Dad's spare"))
        assertEquals("<name>", logSafeDeviceName("Sarah"))
    }

    /** "We saw no name" and "we removed a name" are different facts to a reader, so the sentinel stays. */
    @Test fun logSafeDeviceNameKeepsTheNoNameSentinel() {
        assertEquals("unknown", logSafeDeviceName("unknown"))
        assertEquals("unknown", logSafeDeviceName(null))
        assertEquals("unknown", logSafeDeviceName("   "))
    }


    /** Third-party straps: the MODEL is the diagnostic, so a name carrying no person survives intact. */
    @Test fun logSafeDeviceNameKeepsAnUnrenamedThirdPartyModel() {
        assertEquals("Polar H10", logSafeDeviceName("Polar H10"))
        assertEquals("TICKR", logSafeDeviceName("TICKR"))
        assertEquals("WHOOP 4.0", logSafeDeviceName("WHOOP 4.0"))
    }

    /** ...but a renamed one loses the person and keeps the model. */
    @Test fun logSafeDeviceNameStripsThePersonFromARenamedThirdPartyStrap() {
        assertEquals("<name> Polar H10", logSafeDeviceName("Ryan's Polar H10"))
        assertEquals("<name> TICKR", logSafeDeviceName("Sarah TICKR"))
    }

    /**
     * #2337: the rename path must hand the USER-CHOSEN name to [logSafeDeviceName] before it reaches the
     * log, and must never interpolate the raw value.
     *
     * This is the one string in that path that can carry a person's name. [redactStrapLogPii] cannot save
     * it after the fact: it masks MACs, WHOOP serials and hex dumps, and a name is none of those, as the
     * bare-serial case above already records. The discovery path routes the very same value through the
     * helper, so before this the identical data was handled two different ways depending on which line
     * printed it.
     *
     * Asserted against the SOURCE because `renameStrap` needs a bonded GATT link and has no unit seam.
     * Same approach as `ChargingAndReleaseTest`, and the same reason.
     */
    @Test fun `the rename log redacts the chosen name`() {
        // The WHOLE function body, not just the one line that carries the name today. Pinning a single
        // line by its text would let a NEW log added beside it leak freely while this still passed, and
        // the leak does not care which line it rides on.
        val body = renameStrapBody()
        assertTrue(
            "the rename log line was not found in renameStrap",
            body.contains("Strap rename: advertising name="),
        )
        assertTrue(
            "the rename log must pass the name through logSafeDeviceName:\n$body",
            body.contains("logSafeDeviceName("),
        )
        // Strip the helper calls, then NO log line in the body may still interpolate a raw name. An
        // earlier version keyed on the `name=` slot of one line; a mutation logging BOTH forms
        // (`name=${logSafeDeviceName(clamped)} raw=$clamped`) walked straight through it.
        val rawInterpolation = Regex("""\$\{?(clamped|name|rawName)\b""")
        val offenders = body.lines()
            .filter { it.contains("log(") }
            .map { it.replace(Regex("""logSafeDeviceName\([^)]*\)"""), "") }
            .filter { rawInterpolation.containsMatchIn(it) }
        assertTrue(
            "no log in renameStrap may carry the raw name: $offenders",
            offenders.isEmpty(),
        )
    }

    /** The source of `renameStrap`, signature to its closing brace. Fails loudly if either end moves. */
    private fun renameStrapBody(): String {
        val src = clientSource()
        val start = src.indexOf("fun renameStrap(rawName: String)")
        if (start < 0) throw IllegalStateException("renameStrap not found in WhoopBleClient.kt")
        val end = src.indexOf("\n    }", start)
        if (end < 0) throw IllegalStateException("renameStrap's closing brace not found")
        return src.substring(start, end)
    }

    private fun clientSource(): String {
        var root = java.io.File(System.getProperty("user.dir") ?: ".").canonicalFile
        repeat(4) {
            val f = java.io.File(root, "android/app/src/main/java/com/noop/ble/WhoopBleClient.kt")
            if (f.isFile) return f.readText()
            root = root.parentFile ?: root
        }
        throw IllegalStateException("WhoopBleClient.kt not found from ${System.getProperty("user.dir")}")
    }

    /**
     * The scan path logs the advertised name of every strap in range, which can be a stranger's
     * "<FirstName>'s Whoop". Every "Discovered" log line must route the name through [logSafeDeviceName].
     * Asserted against the SOURCE because the scan callback needs a live BluetoothLeScanner.
     */
    @Test fun `scan path discovery logs redact the advertised name`() {
        val lines = clientSource().lines().filter { it.contains("log(\"Discovered ") }
        assertTrue("no Discovered log lines found", lines.size >= 2)
        val offenders = lines.filter { !it.contains("logSafeDeviceName(") || it.contains("\$name") }
        assertTrue("Discovered logs must use logSafeDeviceName, not the raw name: $offenders", offenders.isEmpty())
    }
}
