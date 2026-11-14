package uk.jj.grab

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

/**
 * Where finished downloads live.
 *  - Gallery: Movies/Grab and Music/Grab, through MediaStore, visible to Gallery and other apps.
 *  - Private: a folder inside Grab's own storage that no other app (and so not Gallery) can see.
 *    Those files are removed by Android if Grab is uninstalled.
 */
object Library {
    private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")

    fun privateDir(ctx: Context): File = File(ctx.filesDir, "private").apply { mkdirs() }

    fun isAudio(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    /** Moves a finished file into the private folder, never overwriting an existing one. */
    fun keepPrivate(ctx: Context, file: File): Pair<Uri, String> {
        val dir = privateDir(ctx)
        var dest = File(dir, file.name)
        var n = 1
        while (dest.exists()) dest = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
        if (!file.renameTo(dest)) {             // different storage volume: copy, then remove the original
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 16) } }
            file.delete()
        }
        return Uri.fromFile(dest) to dest.name
    }

    /** Copies a finished file into public media storage and returns its content address and name. */
    fun publish(ctx: Context, file: File, audio: Boolean): Pair<Uri, String> {
        val resolver = ctx.contentResolver
        val collection = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: if (audio) "audio/mpeg" else "video/mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, if (audio) "Music/Grab" else "Movies/Grab")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file in ${if (audio) "Music" else "Movies"}/Grab.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                ?: throw IOException("Could not write to shared storage.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) { }
            throw e
        }
        return uri to file.name
    }

    /** Takes a file out of the private folder and puts it in Gallery (Movies/Grab or Music/Grab). */
    fun moveToGallery(ctx: Context, file: File) {
        publish(ctx, file, isAudio(file.name))
        file.delete()
    }
}
