package app.perch

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import org.junit.Assert.*
import org.junit.Test

class DefaultNetworkStateTest {
    @Test fun linkChangesCancelTheOldStreamBeforeStartingItsReplacement() = runBlocking {
        withTimeout(5_000) {
            val state = DefaultNetworkState<String, String>()
            val events = Channel<String>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                state.flow.collectLatest { snapshot ->
                    if (snapshot != null) {
                        try {
                            events.send("start:${snapshot.linkProperties}")
                            awaitCancellation()
                        } finally { events.trySend("stop:${snapshot.linkProperties}") }
                    }
                }
            }
            try {
                state.available("vpn")
                state.linkPropertiesChanged("vpn", "fake-ip-dns")
                assertEquals("start:fake-ip-dns", events.receive())
                state.linkPropertiesChanged("vpn", "new-dns")
                assertEquals("stop:fake-ip-dns", events.receive())
                assertEquals("start:new-dns", events.receive())
                state.linkPropertiesChanged("vpn", "new-dns")
                yield()
                assertTrue(events.tryReceive().isFailure)
                state.lost("vpn")
                assertEquals("stop:new-dns", events.receive())
                yield()
                assertTrue(events.tryReceive().isFailure)
            } finally { collector.cancelAndJoin(); events.close() }
        }
    }

    @Test fun dnsChangesOnTheSameNetworkRestartReception() {
        val state = DefaultNetworkState<String, List<String>>()
        state.available("vpn")
        state.linkPropertiesChanged("vpn", listOf("198.18.0.2"))
        val previous = state.flow.value
        state.linkPropertiesChanged("vpn", listOf("192.168.1.1"))
        assertNotEquals("DNS changes must invalidate the previous reception session", previous, state.flow.value)
        assertEquals(listOf("192.168.1.1"), state.flow.value?.linkProperties)
    }

    @Test fun newNetworkWaitsForItsDnsAndRoutes() {
        val state = DefaultNetworkState<String, String>()
        state.available("wifi")
        assertNull("onAvailable precedes DNS and route delivery", state.flow.value)
        state.linkPropertiesChanged("wifi", "wifi-dns")
        assertEquals("wifi", state.flow.value?.network)
        state.available("vpn")
        assertNull(state.flow.value)
        state.linkPropertiesChanged("vpn", "fake-ip-dns")
        assertEquals("fake-ip-dns", state.flow.value?.linkProperties)
    }

    @Test fun lateCallbacksFromOldNetworkDoNotInterruptVpn() {
        val state = DefaultNetworkState<String, String>()
        state.available("wifi")
        state.linkPropertiesChanged("wifi", "wifi-dns")
        state.available("vpn")
        state.linkPropertiesChanged("vpn", "vpn-dns")
        val vpn = state.flow.value
        state.lost("wifi")
        state.linkPropertiesChanged("wifi", "late-wifi-dns")
        assertSame(vpn, state.flow.value)
    }

    @Test fun duplicateCallbacksDoNotReconnectAndLossStopsReception() {
        val state = DefaultNetworkState<String, String>()
        state.available("vpn")
        state.linkPropertiesChanged("vpn", "dns-and-routes")
        val connected = state.flow.value
        state.available("vpn")
        state.linkPropertiesChanged("vpn", "dns-and-routes")
        assertSame(connected, state.flow.value)
        state.lost("vpn")
        assertNull(state.flow.value)
        state.linkPropertiesChanged("vpn", "late-dns")
        assertNull(state.flow.value)
        state.available("wifi")
        state.linkPropertiesChanged("wifi", "wifi-dns")
        assertEquals("wifi", state.flow.value?.network)
    }
}
