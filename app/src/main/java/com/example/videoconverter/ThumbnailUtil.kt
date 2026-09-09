package com.example.videoconverter

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever

/**
 * 用 MediaMetadataRetriever 均匀取帧生成缩略图列表，不依赖任何播放器。
 */
object ThumbnailUtil {

    data class Thumb(val timeSec: Double, val bitmap: Bitmap)

    fun extractThumbnails(path: String, count: Int = 8): List<Thumb> {
        val retriever = MediaMetadataRetriever()
        val result = mutableListOf<Thumb>()
        try {
            retriever.setDataSource(path)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) return emptyList()

            val stepMs = durationMs / (count + 1)
            for (i in 1..count) {
                val timeUs = (stepMs * i) * 1000L
                val bmp = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (bmp != null) {
                    result.add(Thumb(timeUs / 1_000_000.0, bmp))
                }
            }
        } catch (e: Exception) {
            // 部分损坏文件/不支持的编码取帧会失败，忽略即可，返回已取到的部分
        } finally {
            try { retriever.release() } catch (e: Exception) {}
        }
        return result
    }
}
