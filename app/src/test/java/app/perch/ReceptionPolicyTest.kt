package app.perch

import org.junit.Assert.*
import org.junit.Test

class ReceptionPolicyTest {
    @Test fun transientFailuresBackOffAndRemainCapped() {
        assertEquals(2_000L, ReceptionPolicy.retryMillis(0))
        assertEquals(8_000L, ReceptionPolicy.retryMillis(2))
        assertEquals(300_000L, ReceptionPolicy.retryMillis(30))
        assertEquals(300_000L, ReceptionPolicy.retryMillis(Int.MAX_VALUE))
    }
    @Test fun invalidCredentialsDoNotCauseRapidReconnects() {
        assertEquals(300_000L, ReceptionPolicy.retryMillis(0, 401))
        assertEquals(300_000L, ReceptionPolicy.retryMillis(0, 403))
        assertEquals(2_000L, ReceptionPolicy.retryMillis(0, 503))
    }
    @Test fun throttlingHonorsServerDelayWithoutOverflow() {
        assertEquals(120_000L, ReceptionPolicy.retryMillis(0, 429, 120))
        assertEquals(86_400_000L, ReceptionPolicy.retryMillis(0, 429, Long.MAX_VALUE))
        assertEquals(2_000L, ReceptionPolicy.retryMillis(0, 429, -1))
    }
}
