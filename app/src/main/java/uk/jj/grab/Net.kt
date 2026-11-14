package uk.jj.grab

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/** Whether the phone is currently on Wi-Fi (or a cable), for the Wi-Fi-only setting. */
object Net {
    val wifi = MutableStateFlow(true)

    /** May downloads run right now under the Wi-Fi-only setting? */
    val downloadsAllowed: Boolean get() = !Prefs.wifiOnly.value || wifi.value

    private fun isWifi(caps: NetworkCapabilities?): Boolean =
        caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))

    fun init(ctx: Context, onWifiBack: () -> Unit) {
        try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            wifi.value = isWifi(cm.getNetworkCapabilities(cm.activeNetwork))
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val now = isWifi(caps)
                    val was = wifi.value
                    wifi.value = now
                    if (now && !was) onWifiBack()
                }

                override fun onLost(network: Network) {
                    wifi.value = false
                }
            })
        } catch (e: Exception) {
            Log.w(App.TAG, "network watch failed: ${e.message}")
        }
    }
}
