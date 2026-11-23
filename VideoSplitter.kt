package com.brayan.videosplitter

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.nio.ByteBuffer

data class VideoInfo(val name: String, val durationUs: Long)

/**
 * Découpe SANS ré-encodage (MediaExtractor + MediaMuxer) : très rapide, aucune perte de qualité.
 * Les coupures tombent sur des images-clés, donc chaque partie dure environ la durée demandée.
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
            return VideoInfo(name, ms * 1000L)
        } finally {
            r.release()
        }
    }

    private fun rotationOf(ctx: Context, uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            r.release()
        }
    }

    /** Retourne le nombre de parties créées. */
    fun split(
        ctx: Context,
        uri: Uri,
        segmentUs: Long,
        baseName: String,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean
    ): Int {
        // 1) Repérer toutes les images-clés de la piste vidéo
        val scan = MediaExtractor()
        val syncs = ArrayList<Long>()
        try {
            scan.setDataSource(ctx, uri, null)
            val v = (0 until scan.trackCount).firstOrNull {
                scan.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("Aucune piste vidéo trouvée")
            scan.selectTrack(v)
            while (true) {
                val t = scan.sampleTime
                if (t < 0) break
                if (scan.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) syncs.add(t)
                scan.advance()
            }
        } finally {
            scan.release()
        }

        // 2) Choisir les points de coupe (première image-clé >= cible)
        val cuts = ArrayList<Long>()
        cuts.add(0L)
        var target = segmentUs
        var i = 0
        while (true) {
            while (i < syncs.size && syncs[i] < target) i++
            if (i >= syncs.size) break
            val c = syncs[i]
            if (c > cuts.last()) cuts.add(c)
            target = c + segmentUs
        }

        val rotation = rotationOf(ctx, uri)
        val total = cuts.size
        val stem = baseName.substringBeforeLast('.', baseName)
        var done = 0
        onProgress(0, total)

        for (k in 0 until total) {
            if (isCancelled()) break
            val start = cuts[k]
            val end = if (k + 1 < total) cuts[k + 1] else Long.MAX_VALUE
            val partName = "%s_partie_%03d.mp4".format(stem, k + 1)
            writeSegment(ctx, uri, start, end, partName, rotation)
            done++
            onProgress(done, total)
        }
        return done
    }

    private fun writeSegment(
        ctx: Context, uri: Uri, startUs: Long, endUs: Long, fileName: String, rotation: Int
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val out = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Impossible de créer le fichier de sortie")

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            resolver.openFileDescriptor(out, "rw")!!.use { pfd ->
                extractor.setDataSource(ctx, uri, null)
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val m = muxer!!
                if (rotation != 0) m.setOrientationHint(rotation)

                val map = HashMap<Int, Int>()
                for (t in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(t)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        try {
                            map[t] = m.addTrack(f)
                            extractor.selectTrack(t)
                        } catch (e: Exception) {
                            // piste non supportée par le MP4 : on l'ignore
                        }
                    }
                }
                if (map.isEmpty()) throw IllegalStateException("Format non compatible MP4")

                extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                m.start()
                started = true

                val buf = ByteBuffer.allocate(4 * 1024 * 1024)
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val t = extractor.sampleTime
                    if (t < 0 || t >= endUs) break
                    val mi = map[extractor.sampleTrackIndex]
                    if (mi == null || t < startUs) {
                        extractor.advance()
                        continue
                    }
                    buf.clear()
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                        MediaCodec.BUFFER_FLAG_SYNC_FRAME else 0
                    info.set(0, size, t - startUs, flags)
                    m.writeSampleData(mi, buf, info)
                    extractor.advance()
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(out, values, null, null)
        } catch (e: Exception) {
            resolver.delete(out, null, null)
            throw e
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }
}
