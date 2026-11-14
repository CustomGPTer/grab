package uk.jj.grab

import android.content.Context
import android.util.Log
import java.util.Arrays
import kotlin.concurrent.thread

/**
 * Ad / tracker host blocking for the in-app browser.
 *
 * The bundled list (assets/adhosts.txt, first line = entry count) is the StevenBlack unified hosts list.
 * Hosts are held as sorted 64-bit hashes, so ~77k entries cost about 0.6 MB of memory and a lookup is
 * a handful of binary searches.
 */
object AdBlock {
    @Volatile private var table = LongArray(0)
    @Volatile private var off: Set<String> = emptySet()

    fun init(ctx: Context) {
        off = Prefs.adblockOff
        thread(name = "adblock-load", isDaemon = true) {
            try {
                ctx.assets.open("adhosts.txt").bufferedReader().use { load(it) }
            } catch (e: Exception) {
                Log.w(App.TAG, "blocklist load failed", e)
            }
        }
    }

    /** Reads the list: first line is the number of hosts, then one host per line. */
    internal fun load(r: java.io.BufferedReader) {
        val n = r.readLine()?.trim()?.toIntOrNull() ?: 0
        val t = LongArray(n)
        var i = 0
        while (i < n) {
            val line = r.readLine() ?: break
            t[i++] = hash(line, 0)
        }
        val filled = if (i == n) t else t.copyOf(i)
        Arrays.sort(filled)
        table = filled
    }

    private fun hash(s: CharSequence, from: Int): Long {
        var x = 0xcbf29ce484222325UL.toLong()
        for (i in from until s.length) {
            x = x xor s[i].code.toLong()
            x *= 0x100000001b3L
        }
        return x
    }

    /** True if the host, or any parent domain of it, is on the list. */
    fun isBlocked(host: String?): Boolean {
        val t = table
        if (host.isNullOrEmpty() || t.isEmpty()) return false
        var i = 0
        while (true) {
            if (Arrays.binarySearch(t, hash(host, i)) >= 0) return true
            val dot = host.indexOf('.', i)
            if (dot < 0 || host.indexOf('.', dot + 1) < 0) return false   // never test a bare top-level suffix
            i = dot + 1
        }
    }

    /** Is blocking switched on for the site currently being viewed? */
    fun enabled(pageHost: String?): Boolean = Prefs.adBlock.value && baseDomain(pageHost) !in off

    /** Block a request? Never blocks the site's own hosts, only third parties on the list. */
    fun shouldBlock(requestHost: String?, pageHost: String?): Boolean =
        enabled(pageHost) && !sameSite(requestHost, pageHost) && isBlocked(requestHost)

    /** Flip blocking for one site. Returns the new state (true = blocking on). */
    fun toggle(pageHost: String?): Boolean {
        val key = baseDomain(pageHost)
        if (key.isEmpty()) return true
        val next = if (key in off) off - key else off + key
        off = next
        Prefs.adblockOff = next
        return key !in next
    }
}
