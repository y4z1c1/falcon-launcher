package dev.yusuf.falconlauncher.util

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

/** This TV's IPv4 address on the current network, e.g. "192.168.0.28". */
fun localIpv4(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
        ?.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
}
