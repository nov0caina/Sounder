package org.nov0caina.sounder.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

interface ConnectivityObserver {
    val isConnected: StateFlow<Boolean>
    val currentConnection: Boolean
}

@Singleton
class NetworkConnectivityObserver(
    private val connectivityManager: ConnectivityManager?,
    initialState: Boolean? = null
) : ConnectivityObserver {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    )

    private val _isConnected = MutableStateFlow(initialState ?: checkCurrent())
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    override val currentConnection: Boolean
        get() = _isConnected.value

    val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isConnected.value = true
        }

        override fun onLost(network: Network) {
            _isConnected.value = checkCurrent()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            _isConnected.value = hasInternet
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Throwable) {
            // Handled safely in unit test or constrained environment
        }
    }

    fun updateConnection(connected: Boolean) {
        _isConnected.value = connected
    }

    private fun checkCurrent(): Boolean {
        val cm = connectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

@Module
@InstallIn(SingletonComponent::class)
interface ConnectivityModule {
    @Binds
    @Singleton
    fun bindConnectivityObserver(impl: NetworkConnectivityObserver): ConnectivityObserver
}
