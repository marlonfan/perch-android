package app.perch

/** Notification policy only; Android owns playback and its lifecycle. */
internal object PriorityAlerts {
    fun vibration(priority: Int): LongArray? = when (priority) {
        4 -> longArrayOf(0, 250, 150, 250)
        5 -> longArrayOf(0, 1000, 250, 1000, 250, 1000)
        else -> null
    }

    fun eligible(snapshot: Snapshot, message: Message): Boolean {
        val topic = snapshot.topicFor(message) ?: return false
        val server = snapshot.servers.find { it.url == message.server } ?: return false
        return server.enabled && server.lockedToken.isBlank() && !message.read &&
            message.event == "message" && message.priority >= 2 &&
            message.time >= topic.joined && topic.shouldNotify(message)
    }

    fun persistent(snapshot: Snapshot, message: Message, alert: Boolean, liveAfter: Long?): Boolean =
        alert && liveAfter != null && liveAfter > 0 && message.time > liveAfter &&
            message.priority == 5 && snapshot.topicFor(message)?.persistentAlert == true &&
            eligible(snapshot, message)

    // The open event carries server time. Its second may contain cached messages.
    fun openTime(line: String): Long? = runCatching {
        val event = org.json.JSONObject(line)
        event.optLong("time").takeIf { event.optString("event") == "open" && it > 0 }
    }.getOrNull()
}
