package app.perch

import org.junit.Assert.*
import org.junit.Test

class ReceptionPolicyTest {
    @Test fun connectionErrorsIdentifyDnsTimeoutRoutesAndTlsWithoutLeakingDetails() {
        val secret = "private.example/token"
        val cases = listOf(
            java.net.UnknownHostException(secret) to "DNS 解析失败",
            java.net.SocketTimeoutException(secret) to "网络请求超时",
            java.net.ConnectException(secret) to "无法连接服务器",
            javax.net.ssl.SSLHandshakeException(secret) to "TLS 连接失败",
            java.io.IOException(secret) to "网络不可用"
        )
        cases.forEach { (error, expected) ->
            val status = ReceptionPolicy.failureStatus(error)
            assertTrue(status.startsWith(expected))
            assertFalse(status.contains(secret))
        }
    }
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
