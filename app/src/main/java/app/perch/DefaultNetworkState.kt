package app.perch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Keeps callback ordering and equality independent of Android for regression tests. */
internal class DefaultNetworkState<N, L> {
    data class Snapshot<N, L>(val network: N, val linkProperties: L)
    private var current: N? = null
    private val mutable = MutableStateFlow<Snapshot<N, L>?>(null)
    val flow = mutable.asStateFlow()

    @Synchronized fun available(network: N) {
        if (current == network) return
        current = network
        // Android delivers link properties after availability. Stop the old stream now,
        // but wait for the new DNS/routes before opening its replacement.
        mutable.value = null
    }

    @Synchronized fun linkPropertiesChanged(network: N, properties: L) {
        if (current == network) mutable.value = Snapshot(network, properties)
    }

    @Synchronized fun lost(network: N) {
        if (current == network) { current = null; mutable.value = null }
    }
}
