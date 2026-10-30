package app.perch

/** Pure policy, separate from Android network callbacks and coroutine scheduling. */
internal object ReceptionPolicy {
    // Never include exception messages: they can contain private server addresses.
    fun failureStatus(error: Exception): String {
        // Bound traversal also handles malformed cyclic exception chains.
        val causes = generateSequence(error as Throwable) { it.cause }.take(16).toList()
        val description = when {
            causes.any { it is java.security.cert.CertificateExpiredException || it is java.security.cert.CertPathValidatorException && it.reason == java.security.cert.CertPathValidatorException.BasicReason.EXPIRED } ->
                "TLS 证书已过期，请检查系统时间与服务证书 [TLS_CERT_EXPIRED]"
            causes.any { it is java.security.cert.CertificateNotYetValidException || it is java.security.cert.CertPathValidatorException && it.reason == java.security.cert.CertPathValidatorException.BasicReason.NOT_YET_VALID } ->
                "TLS 证书尚未生效，请检查系统时间与服务证书 [TLS_CERT_NOT_YET_VALID]"
            causes.any { it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException } ->
                "TLS 证书链校验失败，请检查完整证书链与代理证书 [TLS_CERT_CHAIN]"
            causes.any { it is javax.net.ssl.SSLPeerUnverifiedException } ->
                "TLS 身份校验失败，请检查域名与代理证书 [TLS_PEER_UNVERIFIED]"
            causes.any { it is javax.net.ssl.SSLProtocolException } ->
                "TLS 协议协商失败，请检查代理节点与协议兼容性 [TLS_PROTOCOL]"
            causes.any { it is javax.net.ssl.SSLHandshakeException } ->
                "TLS 握手失败，请检查代理节点与网络 [TLS_HANDSHAKE]"
            causes.any { it is javax.net.ssl.SSLException } ->
                "TLS 通信失败，请检查代理与网络 [TLS_IO]"
            causes.any { it is java.net.UnknownHostException } ->
                "DNS 解析失败，请检查 VPN/代理的 DNS 设置"
            causes.any { it is java.net.SocketTimeoutException } -> "网络请求超时，请检查代理与网络"
            causes.any { it is java.net.ConnectException } -> "无法连接服务器，请检查代理与路由"
            else -> "网络不可用"
        }
        return "$description，稍后自动重连"
    }

    fun retryMillis(attempt: Int, statusCode: Int? = null, retryAfterSeconds: Long? = null): Long {
        val backoff = (2_000L * (1L shl attempt.coerceIn(0, 8))).coerceAtMost(300_000L)
        val minimum = if (statusCode != null && statusCode in 400..499 && statusCode != 408 && statusCode != 429) 300_000L else backoff
        val requested = retryAfterSeconds?.coerceIn(0, 86_400)?.times(1_000) ?: 0L
        return maxOf(backoff, minimum, requested)
    }
}
