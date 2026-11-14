package uk.jj.grab

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Hands the in-app browser's cookies for one site to the download engine, as a temporary
 * Netscape-format cookie file. Only used after a download has already failed without them.
 * The file lives in the app's private cache and is deleted as soon as the engine has run.
 */
object Cookies {

    /** The private-mode browser's own cookie store, while private mode is on. */
    @Volatile var privateJar: CookieManager? = null

    private fun jar(private: Boolean): CookieManager? = try {
        if (private) privateJar ?: CookieManager.getInstance() else CookieManager.getInstance()
    } catch (_: Exception) { null }

    private fun get(private: Boolean, url: String?): String? =
        if (url.isNullOrBlank()) null else try { jar(private)?.getCookie(url) } catch (_: Exception) { null }

    /** Does the browser hold any cookies for any of these addresses? */
    fun has(private: Boolean, vararg urls: String?): Boolean = urls.any { !get(private, it).isNullOrBlank() }

    fun write(ctx: Context, id: String, urls: List<String?>, private: Boolean): File? {
        val expiry = System.currentTimeMillis() / 1000 + 86_400
        val lines = LinkedHashMap<String, String>()
        for (url in urls.filterNotNull().distinct()) {
            val raw = get(private, url) ?: continue
            val domain = baseDomain(hostOf(url))
            if (domain.isEmpty()) continue
            for (pair in raw.split(';')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val name = pair.substring(0, eq).trim()
                val value = pair.substring(eq + 1).trim()
                if (name.isEmpty() || name.any { it == '\t' || it == '\n' } || value.any { it == '\t' || it == '\n' }) continue
                // domain, include-subdomains, path, secure-only, expiry, name, value
                lines["$domain\t$name"] = ".$domain\tTRUE\t/\tFALSE\t$expiry\t$name\t$value"
            }
        }
        if (lines.isEmpty()) return null
        val f = File(ctx.cacheDir, "ck-$id.txt")
        f.writeText("# Netscape HTTP Cookie File\n" + lines.values.joinToString("\n") + "\n")
        return f
    }
}
