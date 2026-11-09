package org.nov0caina.sounder.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityObserverTest {

    @Test
    fun testInitialOfflineState() = runTest {
        val observer = NetworkConnectivityObserver(connectivityManager = null, initialState = false)
        assertFalse(observer.currentConnection)
        assertEquals(false, observer.isConnected.first())
    }

    @Test
    fun testInitialOnlineState() = runTest {
        val observer = NetworkConnectivityObserver(connectivityManager = null, initialState = true)
        assertTrue(observer.currentConnection)
        assertEquals(true, observer.isConnected.first())
    }

    @Test
    fun testUpdateConnectionState() = runTest {
        val observer = NetworkConnectivityObserver(connectivityManager = null, initialState = false)
        assertFalse(observer.currentConnection)

        observer.updateConnection(true)
        assertTrue(observer.currentConnection)
        assertEquals(true, observer.isConnected.first())

        observer.updateConnection(false)
        assertFalse(observer.currentConnection)
        assertEquals(false, observer.isConnected.first())
    }
}
