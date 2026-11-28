package com.brayan.videosplitter

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil

data class VideoInfo(val name: String, val durationMs: Long)

/** segMs = durée d'une partie, parts = nombre de parties (la dernière va jusqu'à la fin). */
data class Plan(val segMs: Long, val parts: Int)

class StallException : Exception("Aucune progression")

/**
 * Découpe EXACTE avec Media3 Transformer.
 * 1re tentative : mode rapide (ne ré-encode que le début de chaque partie).
 * Si ça échoue ou se fige : 2e tentative en mode complet (plus lent mais sûr).
 */
object VideoSplitter {

    private const val FOLDER = "DecoupeurVideo"
    private const val STALL_MS = 90_000L

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
            if (parts > 1 && durationMs - (parts - 1) * seg < 1000L) parts--
            return Plan(seg, maxOf(1, parts))
        }
        if (n > 100_000) return null
        val parts = n.toInt()
        val seg = durationMs / parts
        if (seg < 1000L) return null
        return Plan(seg, parts)
    }

    /** Doit être appelée depuis le thread principal. Les Uri créées sont ajoutées à [results]. */
    suspend fun split(
        ctx: Context,
        uri: Uri,
        plan: Plan,
        durationMs: Long,
        baseName: String,
        results: MutableList<Uri>,
        onProgress: (done: Int, total: Int, pct: Int) -> Unit
    ) {
        val stem = baseName.substringBeforeLast('.', baseName)
        onProgress(0, plan.parts, 0)

        for (k in 0 until plan.parts) {
            val startMs = k * plan.segMs
            val last = k == plan.parts - 1
            val endMs = if (last) durationMs else (k + 1) * plan.segMs
            val tmp = File(ctx.cacheDir, "partie_tmp.mp4")
            try {
                tmp.delete()
                try {
                    exportClip(ctx, uri, startMs, endMs, tmp, true) { onProgress(k, plan.parts, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    tmp.delete()
                    val endFull = if (last) C.TIME_END_OF_SOURCE else endMs
                    exportClip(ctx, uri, startMs, endFull, tmp, false) { onProgress(k, plan.parts, it) }
                }
                val name = "%s_partie_%03d.mp4".format(stem, k + 1)
                val out = withContext(Dispatchers.IO) { saveToGallery(ctx, tmp, name) }
                results.add(out)
            } finally {
                tmp.delete()
            }
            onProgress(k + 1, plan.parts, 0)
        }
    }

    private suspend fun exportClip(
        ctx: Context, uri: Uri, startMs: Long, endMs: Long, out: File,
        fast: Boolean, onPct: (Int) -> Unit
    ) {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clip).build()
        val edited = EditedMediaItem.Builder(item).build()

        val finished = CompletableDeferred<Unit>()
        val builder = Transformer.Builder(ctx)
        if (fast) {
            runCatching {
                builder.javaClass
                    .getMethod("experimentalSetTrimOptimizationEnabled", java.lang.Boolean.TYPE)
                    .invoke(builder, true)
            }
        }
        val transformer = builder
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finished.completeExceptionally(exportException)
                }
            })
            .build()

        transformer.start(edited, out.absolutePath)

        val holder = ProgressHolder()
        var lastPct = -1
        var lastChange = SystemClock.elapsedRealtime()
        try {
            while (!finished.isCompleted) {
                delay(500)
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val p = holder.progress
                    onPct(p)
                    if (p != lastPct) {
                        lastPct = p
                        lastChange = SystemClock.elapsedRealtime()
                    }
                }
                if (fast && SystemClock.elapsedRealtime() - lastChange > STALL_MS) {
                    throw StallException()
                }
            }
            finished.await()
        } catch (e: Throwable) {
            transformer.cancel()
            throw e
        }
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
