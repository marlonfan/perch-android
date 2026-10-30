package app.perch

/** Pure policy, separate from Android network callbacks and coroutine scheduling. */
internal object ReceptionPolicy {
    // Never include exception messages: they can contain private server addresses.
    fun failureStatus(error: Exception): String = when (error) {
        is java.net.UnknownHostException -> "DNS 解析失败，请检查 VPN/代理的 DNS 设置，稍后自动重连"
        is java.net.SocketTimeoutException -> "网络请求超时，请检查代理与网络，稍后自动重连"
        is java.net.ConnectException -> "无法连接服务器，请检查代理与路由，稍后自动重连"
        is javax.net.ssl.SSLException -> "TLS 连接失败，请检查证书与代理设置，稍后自动重连"
        else -> "网络不可用，稍后自动重连"
    }

    fun retryMillis(attempt: Int, statusCode: Int? = null, retryAfterSeconds: Long? = null): Long {
        val backoff = (2_000L * (1L shl attempt.coerceIn(0, 8))).coerceAtMost(300_000L)
        val minimum = if (statusCode != null && statusCode in 400..499 && statusCode != 408 && statusCode != 429) 300_000L else backoff
        val requested = retryAfterSeconds?.coerceIn(0, 86_400)?.times(1_000) ?: 0L
        return maxOf(backoff, minimum, requested)
    }
}
