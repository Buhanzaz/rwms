package dev.buhanzaz.rwms.client.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Outcome of one admitted read cycle; skipped work never consumes the retry budget. */
internal enum class CustomerReadOutcome { SUCCESS, FAILED, SKIPPED }

/** Finite retries use elapsed time; lifecycle or explicit user activity starts a fresh read budget. */
internal class CustomerAutomaticReadPolicy {
    private var consecutiveFailures = 0
    private var nextAttemptAtMillis = 0L

    val paused: Boolean get() = consecutiveFailures >= 5

    fun canAttempt(nowMillis: Long): Boolean = !paused && nowMillis >= nextAttemptAtMillis

    fun reset() {
        consecutiveFailures = 0
        nextAttemptAtMillis = 0L
    }

    fun record(outcome: CustomerReadOutcome, nowMillis: Long) {
        when (outcome) {
            CustomerReadOutcome.SKIPPED -> return
            CustomerReadOutcome.SUCCESS -> {
                consecutiveFailures = 0
                nextAttemptAtMillis = nowMillis + 5_000L
            }
            CustomerReadOutcome.FAILED -> {
                consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                nextAttemptAtMillis = if (paused) Long.MAX_VALUE
                else nowMillis + (5_000L shl (consecutiveFailures - 1))
            }
        }
    }
}

/** Reports validated default-network transitions only while collected by the foreground lifecycle. */
internal fun customerValidatedConnectivity(context: Context): Flow<Boolean> = callbackFlow {
    val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    fun NetworkCapabilities?.isValidatedInternet(): Boolean = this != null &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    val callback = object : ConnectivityManager.NetworkCallback() {
        private var currentNetwork: Network? = null

        override fun onAvailable(network: Network) {
            currentNetwork = network
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == currentNetwork) trySend(capabilities.isValidatedInternet())
        }

        override fun onLost(network: Network) {
            if (network == currentNetwork) {
                currentNetwork = null
                trySend(false)
            }
        }
    }
    trySend(manager.getNetworkCapabilities(manager.activeNetwork).isValidatedInternet())
    manager.registerDefaultNetworkCallback(callback)
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
