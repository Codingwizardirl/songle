package me.pavelgeorgiev.songle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkInfo

/**
 * Receiver tracks availability of network
 */
class NetworkReceiver : BroadcastReceiver() {
    companion object {
        /**
         * Checks if device is connected to the Internet
         */
        fun isNetworkConnected(context: Context): Boolean {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = connectivityManager.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
        }
    }

    private var listeners = HashSet<NetworkStateReceiverListener>()
    private var connected: Boolean? = null

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null || intent.extras == null)
            return

        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val ni = manager.activeNetworkInfo

        if (ni != null && ni.state == NetworkInfo.State.CONNECTED) {
            connected = true
        } else if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, java.lang.Boolean.FALSE)) {
            connected = false
        }

        notifyStateToAll()
    }

    /**
     * Notifies all listeners that the state of the network changed
     */
    private fun notifyStateToAll() {
        for (listener in listeners)
            notifyState(listener)
    }

    /**
     * Calls callback functions in the listener
     */
    private fun notifyState(listener: NetworkStateReceiverListener?) {
        if (connected == null || listener == null)
            return

        if (connected === true)
            listener.networkAvailable()
        else
            listener.networkUnavailable()
    }

    fun addListener(l: NetworkStateReceiverListener) {
        listeners.add(l)
        notifyState(l)
    }

    fun removeListener(l: NetworkStateReceiverListener) {
        listeners.remove(l)
    }

    interface NetworkStateReceiverListener {
        fun networkAvailable()
        fun networkUnavailable()
    }
}
