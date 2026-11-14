package uk.jj.grab

import android.content.Context
import android.net.Uri
import java.io.File

private val TWO_PART_SUFFIX = setOf("co", "com", "org", "net", "gov", "ac", "edu")

/** "www.bbc.co.uk" -> "bbc.co.uk". A rough registrable-domain guess, good enough for grouping one site's hosts. */
fun baseDomain(host: String?): String {
    val h = host?.lowercase()?.trim('.') ?: return ""
    val parts = h.split('.')
    if (parts.size <= 2) return h
    val twoPart = parts.last().length == 2 && parts[parts.size - 2] in TWO_PART_SUFFIX
    return parts.takeLast(if (twoPart) 3 else 2).joinToString(".")
}

fun hostOf(url: String?): String? =
    if (url.isNullOrBlank()) null else try { Uri.parse(url).host?.lowercase() } catch (_: Exception) { null }

fun sameSite(a: String?, b: String?): Boolean {
    val x = baseDomain(a)
    return x.isNotEmpty() && x == baseDomain(b)
}

/** "https://example.com/a/b" -> "https://example.com" */
fun originOf(url: String?): String? {
    if (url.isNullOrBlank()) return null
    return try {
        val u = Uri.parse(url)
        val scheme = u.scheme ?: return null
        val host = u.host ?: return null
        if (u.port > 0) "$scheme://$host:${u.port}" else "$scheme://$host"
    } catch (_: Exception) { null }
}

fun fmtEta(s: Long): String = when {
    s <= 0 -> ""
    s < 60 -> "${s}s left"
    s < 3600 -> "${s / 60}m ${s % 60}s left"
    else -> "${s / 3600}h ${(s % 3600) / 60}m left"
}

fun humanSize(b: Long): String {
    val mb = b / 1_048_576.0
    return when {
        mb >= 1024 -> "%.2f GB".format(mb / 1024)
        mb >= 1 -> "%.0f MB".format(mb)
        else -> "%.0f KB".format(b / 1024.0)
    }
}

/** A page title made safe to use as a file name, or null if nothing usable is left. */
fun safeName(raw: String?): String? {
    val s = raw
        ?.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.take(120)
    return if (s.isNullOrBlank()) null else s
}

/** Where in-progress downloads live. One sub-folder per task so partial files can be resumed. */
fun tmpRoot(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tmp")

/** Label put on anything Grab itself copies, so its own text is never offered back as "a link you copied". */
const val OWN_CLIP = "grab"

fun copyText(ctx: Context, text: String) {
    try {
        ctx.getSystemService(android.content.ClipboardManager::class.java)
            ?.setPrimaryClip(android.content.ClipData.newPlainText(OWN_CLIP, text))
    } catch (_: Exception) { /* clipboard refused; nothing useful to do */ }
}
