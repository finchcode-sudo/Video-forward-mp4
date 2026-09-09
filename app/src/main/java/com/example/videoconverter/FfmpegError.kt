package com.example.videoconverter

/**
 * 把 ffmpeg 返回的原始日志尾部，翻译成用户能看懂的中文提示。
 * 匹配不到任何已知模式时，回退为日志原文（截断）。
 */
object FfmpegError {

    private val patterns = listOf(
        "No space left" to "存储空间不足，请清理手机空间后重试",
        "Permission denied" to "没有权限读写该文件，请重新选择文件",
        "Invalid data found" to "文件已损坏或格式不受支持",
        "moov atom not found" to "视频文件不完整或已损坏",
        "does not contain any stream" to "文件中没有可处理的音视频轨道",
        "Unknown encoder" to "设备不支持所选的编码方式",
        "Unsupported codec" to "设备不支持该编码格式",
        "Cannot allocate memory" to "内存不足，请关闭其他应用后重试",
        "Output file is empty" to "生成的文件为空，可能是参数设置有误",
        "Invalid argument" to "参数设置有误，请检查输入的数值",
        "No such file or directory" to "找不到文件，请重新选择",
        "Conversion failed" to "转换失败，请检查输入参数或更换文件",
        "Error while decoding" to "解码出错，视频可能已损坏",
        "Error while opening encoder" to "编码器初始化失败，可能是分辨率或参数不受支持",
        "height not divisible by 2" to "画面高度需要是偶数，请调整裁切/分辨率参数",
        "width not divisible by 2" to "画面宽度需要是偶数，请调整裁切/分辨率参数",
        "Filter not found" to "所选的处理效果在当前设备不受支持",
        "Cannot find a matching stream" to "找不到匹配的音视频轨道"
    )

    fun friendly(rawLogTail: String): String {
        if (rawLogTail.isBlank()) return "未知错误，请重试"
        for ((key, msg) in patterns) {
            if (rawLogTail.contains(key, ignoreCase = true)) return msg
        }
        // 没匹配到已知模式，返回日志最后一行作为参考，避免整段乱码堆砌
        val lastLine = rawLogTail.trim().lines().lastOrNull { it.isNotBlank() } ?: rawLogTail
        return if (lastLine.length > 80) lastLine.takeLast(80) else lastLine
    }
}
