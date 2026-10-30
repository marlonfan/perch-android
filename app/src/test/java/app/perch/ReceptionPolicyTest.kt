package app.perch

import org.junit.Assert.*
import org.junit.Test

class ReceptionPolicyTest {
    @Test fun tlsHandshakeWithoutCertificateCauseDoesNotBlameCertificates() {
        val error = javax.net.ssl.SSLHandshakeException("private.example/token").apply {
            initCause(java.io.EOFException("proxy closed the stream"))
        }
        val status = ReceptionPolicy.failureStatus(error)
        assertTrue(status.contains("TLS_HANDSHAKE"))
        assertFalse(status.contains("证书"))
        assertFalse(status.contains("private.example"))
    }

    @Test fun tlsCertificateCausesAreDistinguishedWithoutRawMessages() {
        val secret = "private.example/token"
        val cases = listOf(
            java.security.cert.CertificateExpiredException(secret) to "TLS_CERT_EXPIRED",
            java.security.cert.CertificateNotYetValidException(secret) to "TLS_CERT_NOT_YET_VALID",
            java.security.cert.CertPathValidatorException(secret) to "TLS_CERT_CHAIN"
        )
        cases.forEach { (cause, code) ->
            val error = javax.net.ssl.SSLHandshakeException(secret).apply { initCause(cause) }
            val status = ReceptionPolicy.failureStatus(error)
            assertTrue("Expected $code", status.contains(code))
            assertFalse(status.contains(secret))
        }
        assertTrue(ReceptionPolicy.failureStatus(javax.net.ssl.SSLPeerUnverifiedException(secret)).contains("TLS_PEER_UNVERIFIED"))
        assertTrue(ReceptionPolicy.failureStatus(javax.net.ssl.SSLProtocolException(secret)).contains("TLS_PROTOCOL"))
    }

    @Test fun connectionErrorsIdentifyDnsTimeoutRoutesAndTlsWithoutLeakingDetails() {
        val secret = "private.example/token"
        val cases = listOf(
            java.net.UnknownHostException(secret) to "DNS 解析失败",
            java.net.SocketTimeoutException(secret) to "网络请求超时",
            java.net.ConnectException(secret) to "无法连接服务器",
            javax.net.ssl.SSLHandshakeException(secret) to "TLS 握手失败",
            java.io.IOException(secret) to "网络不可用"
        )
        cases.forEach { (error, expected) ->
            val status = ReceptionPolicy.failureStatus(error)
            assertTrue(status.startsWith(expected))
            assertFalse(status.contains(secret))
        }
    }
    @Test fun wrappedAndCyclicFailuresAreBoundedAndKeepTheirClassification() {
        val certificate = java.security.cert.CertificateExpiredException("secret")
        val wrapped = java.io.IOException("secret", javax.net.ssl.SSLHandshakeException("secret").apply { initCause(certificate) })
        assertTrue(ReceptionPolicy.failureStatus(wrapped).contains("TLS_CERT_EXPIRED"))
        val first = java.io.IOException("secret")
        val second = java.io.IOException("secret", first)
        first.initCause(second)
        assertEquals("网络不可用，稍后自动重连", ReceptionPolicy.failureStatus(first))
    }
    @Test fun certPathReasonsTakePrecedenceAndGenericTlsErrorsStayGeneric() {
        val cases = listOf(
            java.security.cert.CertPathValidatorException.BasicReason.EXPIRED to "TLS_CERT_EXPIRED",
            java.security.cert.CertPathValidatorException.BasicReason.NOT_YET_VALID to "TLS_CERT_NOT_YET_VALID",
            java.security.cert.CertPathValidatorException.BasicReason.UNSPECIFIED to "TLS_CERT_CHAIN"
        )
        cases.forEach { (reason, code) ->
            val cause = java.security.cert.CertPathValidatorException("secret", null, null, -1, reason)
            val error = javax.net.ssl.SSLHandshakeException("secret").apply { initCause(cause) }
            assertTrue(ReceptionPolicy.failureStatus(error).contains(code))
        }
        val status = ReceptionPolicy.failureStatus(javax.net.ssl.SSLException("secret"))
        assertTrue(status.contains("TLS_IO"))
        assertFalse(status.contains("证书"))
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
