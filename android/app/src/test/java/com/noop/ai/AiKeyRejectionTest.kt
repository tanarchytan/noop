package com.noop.ai

import org.junit.Assert.assertEquals
import org.junit.Test

/** Only 401/403 mean the stored key was turned away; the UI opens a key field on exactly this. */
class AiKeyRejectionTest {
    @Test fun isKeyRejectionMatchesTable() {
        val cases = mapOf(
            401 to true, 403 to true,
            200 to false, 400 to false, 402 to false, 404 to false, 408 to false, 429 to false,
            500 to false, 502 to false, 503 to false, 599 to false, 0 to false, -1 to false,
        )
        for ((code, want) in cases) assertEquals("HTTP $code", want, AiCoach.isKeyRejection(code))
    }
}
