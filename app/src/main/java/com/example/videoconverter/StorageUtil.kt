package com.example.videoconverter

import android.content.Context
import android.os.StatFs
import java.io.File

/**
 * 存储空间检查 + 缓存清理策略：
 * - 每个耗时任务开始前估算所需空间，不够则提前拦截，避免任务跑到一半失败。
 * - App 启动时清理上次可能残留的临时文件（异常退出/被杀进程时留下的）。
 * - 提供手动"清理缓存"入口。
 */
object StorageUtil {

    fun availableBytes(dir: File): Long {
        return try {
            val stat = StatFs(dir.absolutePath)
            stat.availableBytes
        } catch (e: Exception) {
            Long.MAX_VALUE // 获取失败时不拦截用户操作
        }
    }

    /**
     * 估算某个任务大致需要的临时空间：
     * 输入文件本身要拷进 cache 一份，输出文件通常不会比输入大太多，
     * 保留一定安全系数(倍数)。
     */
    fun hasEnoughSpaceFor(context: Context, estimatedInputBytes: Long, multiplier: Double = 3.0): Boolean {
        val need = (estimatedInputBytes * multiplier).toLong().coerceAtLeast(50L * 1024 * 1024)
        return availableBytes(context.cacheDir) > need
    }

    fun cacheSizeBytes(context: Context): Long {
        return context.cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    fun clearCache(context: Context): Long {
        var freed = 0L
        context.cacheDir.listFiles()?.forEach { f ->
            freed += f.length()
            f.delete()
        }
        return freed
    }

    /**
     * 清理超过 maxAgeHours 的残留临时文件（比如上次任务中途被杀进程遗留的输入/输出文件）。
     * 在 App 启动时调用一次即可。
     */
    fun cleanStaleCache(context: Context, maxAgeHours: Long = 24) {
        val cutoff = System.currentTimeMillis() - maxAgeHours * 3600_000
        context.cacheDir.listFiles()?.forEach { f ->
            if (f.lastModified() < cutoff) {
                f.delete()
            }
        }
    }

    fun formatSize(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb >= 1024) "%.2f GB".format(mb / 1024) else "%.1f MB".format(mb)
    }
}
