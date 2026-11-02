package dev.hermeskotlin.core.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop

/** The phone's default network, which changes when Wi-Fi comes and goes. */
class AndroidNetworkChanges(private val context: Context) : NetworkChanges {

    override fun changes(): Flow<Unit> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NoNetworkChanges.changes()
        // Registering reports the network already in use, which is not a change; anything later is.
        val settled = if (manager.activeNetwork != null) 1 else 0
        return callbackFlow {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trySend(Unit)
                }
            }
            manager.registerDefaultNetworkCallback(callback)
            awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
        }.drop(settled)
    }
}
