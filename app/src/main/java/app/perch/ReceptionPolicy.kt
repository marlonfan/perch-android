package app.perch

/** Pure policy, separate from Android network callbacks and coroutine scheduling. */
internal object ReceptionPolicy {
    fun retryMillis(attempt: Int, statusCode: Int? = null, retryAfterSeconds: Long? = null): Long {
        val backoff = (2_000L * (1L shl attempt.coerceIn(0, 8))).coerceAtMost(300_000L)
        val minimum = if (statusCode != null && statusCode in 400..499 && statusCode != 408 && statusCode != 429) 300_000L else backoff
        val requested = retryAfterSeconds?.coerceIn(0, 86_400)?.times(1_000) ?: 0L
        return maxOf(backoff, minimum, requested)
    }
}
