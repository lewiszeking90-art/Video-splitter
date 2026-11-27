package com.brayan.videosplitter

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil

data class VideoInfo(val name: String, val durationMs: Long)

/** segMs = durée d'une partie, parts = nombre de parties (la dernière va jusqu'à la fin). */
data class Plan(val segMs: Long, val parts: Int)

/**
 * Découpe EXACTE (à la seconde près) avec Media3 Transformer.
 * L'optimisation « trim » ne ré-encode que le début de chaque partie, le reste est copié tel quel.
 */
object VideoSplitter {

    private const val FOLDER = "DecoupeurVideo"

    fun readInfo(ctx: Context, uri: Uri): VideoInfo {
        var name = "video"
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: "video"
        }
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            return VideoInfo(name, ms)
        } finally {
            r.release()
        }
    }

    fun plan(durationMs: Long, byDuration: Boolean, n: Long): Plan? {
        if (durationMs <= 0 || n <= 0) return null
        if (byDuration) {
            val seg = n * 1000L
            var parts = ceil(durationMs.toDouble() / seg).toInt()
            // un reste de moins d'une seconde est fusionné avec la partie précédente
            if (parts > 1 && durationMs - (parts - 1) * seg < 1000L) parts--
            return Plan(seg, maxOf(1, parts))
        }
        if (n > 100_000) return null
        val parts = n.toInt()
        val seg = durationMs / parts
        if (seg < 1000L) return null
        return Plan(seg, parts)
    }

    /** Retourne les Uri des fichiers créés (dans Films/DecoupeurVideo). */
    suspend fun split(
        ctx: Context,
        uri: Uri,
        plan: Plan,
        baseName: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Uri> {
        val stem = baseName.substringBeforeLast('.', baseName)
        val results = ArrayList<Uri>()
        onProgress(0, plan.parts)

        for (k in 0 until plan.parts) {
            val startMs = k * plan.segMs
            val endMs = if (k == plan.parts - 1) C.TIME_END_OF_SOURCE else (k + 1) * plan.segMs
            val tmp = File(ctx.cacheDir, "partie_tmp.mp4")
            if (tmp.exists()) tmp.delete()
            try {
                exportClip(ctx, uri, startMs, endMs, tmp) // doit tourner sur le thread principal
                val name = "%s_partie_%03d.mp4".format(stem, k + 1)
                val out = withContext(Dispatchers.IO) { saveToGallery(ctx, tmp, name) }
                results.add(out)
            } finally {
                tmp.delete()
            }
            onProgress(k + 1, plan.parts)
        }
        return results
    }

    private suspend fun exportClip(ctx: Context, uri: Uri, startMs: Long, endMs: Long, out: File) =
        suspendCancellableCoroutine<Unit> { cont ->
            val clip = MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                .setEndPositionMs(endMs)
                .build()
            val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
            val edited = EditedMediaItem.Builder(item).build()

            val builder = Transformer.Builder(ctx)
            // Optimisation : ne ré-encoder que le début (si disponible dans cette version)
            runCatching {
                builder.javaClass
                    .getMethod("experimentalSetTrimOptimizationEnabled", java.lang.Boolean.TYPE)
                    .invoke(builder, true)
            }
            val transformer = builder
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            cont.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { transformer.cancel() }
            }
            transformer.start(edited, out.absolutePath)
        }

    private fun saveToGallery(ctx: Context, file: File, fileName: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val out = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer le fichier de sortie")
        try {
            resolver.openOutputStream(out)!!.use { os ->
                file.inputStream().use { it.copyTo(os) }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(out, values, null, null)
        } catch (e: Exception) {
            resolver.delete(out, null, null)
            throw e
        }
        return out
    }
}
