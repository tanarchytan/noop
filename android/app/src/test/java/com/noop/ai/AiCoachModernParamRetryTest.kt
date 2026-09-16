package com.noop.ai

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The retry gate for models that refuse `temperature` / `max_tokens`: a 400 naming one of them is
 * re-sent on `max_completion_tokens`, anything else is surfaced as the error it is. Plus the prompt
 * number format, which must not follow the device's locale.
 */
class AiCoachModernParamRetryTest {

    @Test
    fun retries_whenTheBodyNamesAClassicParameter() {
        assertTrue(
            AiCoach.namesAClassicParam(
                """{"error":{"message":"Unsupported parameter: 'max_tokens' is not supported with """ +
                    """this model. Use 'max_completion_tokens' instead.","type":"invalid_request_error"}}""",
            ),
        )
        assertTrue(
            AiCoach.namesAClassicParam(
                """{"error":{"message":"Unsupported value: 'temperature' does not support 0.6."}}""",
            ),
        )
    }

    @Test
    fun doesNotRetry_onAnUnrelated400() {
        assertFalse(AiCoach.namesAClassicParam("""{"error":{"message":"Invalid API key provided."}}"""))
        assertFalse(AiCoach.namesAClassicParam("""{"error":{"message":"context_length_exceeded"}}"""))
    }

    /** Forced, not assumed: CI runs in English, where the default locale hides this. */
    @Test
    fun oneDecimal_staysADotOnACommaLocale() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("12.4", AiCoach.fmt1(12.4))
            assertEquals("12", AiCoach.fmt1(12.0))
        } finally {
            Locale.setDefault(original)
        }
    }
}
