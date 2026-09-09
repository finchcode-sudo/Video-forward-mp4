package com.example.videoconverter

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.LogCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.StatisticsCallback
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: android.content.SharedPreferences

    // 记录当前正在跑的ffmpeg任务，用于"取消"按钮
    private var currentSession: FFmpegSession? = null

    // 各个"选文件"按钮点了之后，要走哪条后续逻辑，用这个变量分发
    private enum class PendingAction { CONVERT, TRIM, COMPRESS, EXTRACT_AUDIO, GIF, ROTATE, AUDIO_TRACK_VIDEO, AUDIO_TRACK_AUDIO, INFO }
    private var pendingAction: PendingAction? = null
    private var pendingVideoForAudioTrack: File? = null // "替换音轨"时，先存视频文件，再选音频文件

    // ---------- 各种文件选择器 ----------

    private val pickSingleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { onSingleFilePicked(it) } }

    private val pickMultipleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) onMultipleFilesPicked(uris) }

    private val pickMergeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.size >= 2) mergeVideos(uris)
        else if (uris.isNotEmpty()) statusText.text = "合并至少需要选择2个文件"
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { onAudioForTrackPicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        prefs = getSharedPreferences("app_history", Context.MODE_PRIVATE)

        findViewById<Button>(R.id.pickButton).setOnClickListener {
            pendingAction = PendingAction.CONVERT
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.mergeButton).setOnClickListener {
            pickMergeLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.trimButton).setOnClickListener {
            pendingAction = PendingAction.TRIM
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.compressButton).setOnClickListener {
            pendingAction = PendingAction.COMPRESS
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.extractAudioButton).setOnClickListener {
            pendingAction = PendingAction.EXTRACT_AUDIO
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.gifButton).setOnClickListener {
            pendingAction = PendingAction.GIF
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.rotateButton).setOnClickListener {
            pendingAction = PendingAction.ROTATE
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.audioTrackButton).setOnClickListener {
            pendingAction = PendingAction.AUDIO_TRACK_VIDEO
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.infoButton).setOnClickListener {
            pendingAction = PendingAction.INFO
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }

        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            currentSession?.let {
                FFmpegKit.cancel(it.sessionId)
                statusText.text = "已取消当前任务"
                progressBar.visibility = ProgressBar.INVISIBLE
            } ?: run {
                Toast.makeText(this, "当前没有正在进行的任务", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 分发选择结果 ----------

    private fun onSingleFilePicked(uri: Uri) {
        when (pendingAction) {
            PendingAction.TRIM -> showTrimDialog(uri)
            PendingAction.GIF -> showGifDialog(uri)
            PendingAction.ROTATE -> showRotateDialog(uri)
            PendingAction.AUDIO_TRACK_VIDEO -> showAudioTrackDialog(uri)
            PendingAction.INFO -> showVideoInfo(uri)
            else -> {}
        }
    }

    private fun onMultipleFilesPicked(uris: List<Uri>) {
        when (pendingAction) {
            PendingAction.CONVERT -> showFormatDialog(uris)
            PendingAction.COMPRESS -> showCompressDialog(uris)
            PendingAction.EXTRACT_AUDIO -> extractAudioBatch(uris)
            else -> {}
        }
    }

    private fun onAudioForTrackPicked(audioUri: Uri) {
        val videoFile = pendingVideoForAudioTrack ?: return
        val audioFile = copyUriToCache(audioUri, "audiotrack_${System.currentTimeMillis()}")
        replaceAudioTrack(videoFile, audioFile)
    }

    // ================= 1. 转换格式(支持批量+格式选择) =================

    private fun showFormatDialog(uris: List<Uri>) {
        val formats = arrayOf("mp4", "mkv", "webm")
        AlertDialog.Builder(this)
            .setTitle("选择输出格式(共${uris.size}个文件)")
            .setItems(formats) { _, which ->
                convertBatch(uris, formats[which])
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun convertBatch(uris: List<Uri>, targetFormat: String, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部 ${uris.size} 个文件转换完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            return
        }
        val inputFile = copyUriToCache(uris[index], "input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "converted_${System.currentTimeMillis()}.$targetFormat")

        statusText.text = "[${index + 1}/${uris.size}] 先尝试快速换容器(不重新编码)..."
        progressBar.visibility = ProgressBar.VISIBLE

        val remuxCommand = arrayOf("-y", "-i", inputFile.absolutePath, "-c", "copy", outputFile.absolutePath)

        runFfmpeg(remuxCommand, onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 快速换壳成功，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("格式转换", outputFile.name, "成功(快速换壳)")
            inputFile.delete()
            convertBatch(uris, targetFormat, index + 1)
        }, onFailure = {
            statusText.text = "[${index + 1}/${uris.size}] 该格式不能直接换壳，改用重新编码..."
            val durationMs = getDurationMs(inputFile.absolutePath)
            val command = arrayOf(
                "-y", "-i", inputFile.absolutePath,
                "-c:v", "h264_mediacodec", "-b:v", "4M",
                "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ar", "44100",
                outputFile.absolutePath
            )
            runFfmpeg(command, durationMs, onSuccess = {
                statusText.text = "[${index + 1}/${uris.size}] 转换完成，正在保存..."
                saveToDownloads(outputFile, "video/*")
                addHistory("格式转换", outputFile.name, "成功(重新编码)")
                inputFile.delete()
                convertBatch(uris, targetFormat, index + 1)
            }, onFailure = { msg ->
                statusText.text = "[${index + 1}/${uris.size}] 转换失败: $msg"
                addHistory("格式转换", uris[index].toString(), "失败")
                inputFile.delete()
                convertBatch(uris, targetFormat, index + 1)
            })
        })
    }

    // ================= 2. 合并视频(保留原逻辑，信任-c copy返回码) =================

    private fun mergeVideos(uris: List<Uri>) {
        statusText.text = "准备 ${uris.size} 个文件..."
        progressBar.visibility = ProgressBar.VISIBLE

        val localFiles = uris.mapIndexed { index, uri -> copyUriToCache(uri, "merge_${index}_${System.currentTimeMillis()}") }

        val listFile = File(cacheDir, "concat_list_${System.currentTimeMillis()}.txt")
        listFile.bufferedWriter().use { writer ->
            localFiles.forEach { f ->
                val escapedPath = f.absolutePath.replace("'", "'\\''")
                writer.write("file '$escapedPath'\n")
            }
        }

        val outputFile = File(cacheDir, "merged_${System.currentTimeMillis()}.mp4")
        statusText.text = "合并中(无损快速拼接)..."

        val copyCommand = arrayOf(
            "-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
            "-c", "copy", "-movflags", "+faststart", outputFile.absolutePath
        )

        // 像Termux手动操作一样：直接信任-c copy的返回码，不做事后时长校验
        runFfmpeg(copyCommand, onSuccess = {
            statusText.text = "合并完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("视频合并", outputFile.name, "成功(快速拼接)")
            cleanup(localFiles, listFile)
        }, onFailure = {
            statusText.text = "快速拼接失败，改用重新编码合并..."
            mergeWithReencode(localFiles, listFile, outputFile)
        })
    }

    private fun mergeWithReencode(localFiles: List<File>, listFile: File, outputFile: File) {
        val totalDurationMs = localFiles.sumOf { getDurationMs(it.absolutePath) }

        val dims = localFiles.map { getVideoDimensions(it.absolutePath) }
        val targetWidth = dims.maxOf { it.first }.let { if (it % 2 != 0) it + 1 else it }
        val targetHeight = dims.maxOf { it.second }.let { if (it % 2 != 0) it + 1 else it }

        val filterBuilder = StringBuilder()
        val concatInputs = StringBuilder()
        localFiles.indices.forEach { i ->
            filterBuilder.append(
                "[$i:v:0]scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease," +
                    "pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v$i];"
            )
            filterBuilder.append("[$i:a:0]aformat=sample_rates=44100:channel_layouts=stereo,asetpts=PTS-STARTPTS[a$i];")
            concatInputs.append("[v$i][a$i]")
        }
        filterBuilder.append("${concatInputs}concat=n=${localFiles.size}:v=1:a=1[outv][outa]")

        val inputArgs = localFiles.flatMap { listOf("-i", it.absolutePath) }
        val reencodeCommand = (
            listOf("-y") + inputArgs + listOf(
                "-filter_complex", filterBuilder.toString(),
                "-map", "[outv]", "-map", "[outa]",
                "-c:v", "h264_mediacodec", "-b:v", "4M",
                "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ar", "44100", "-movflags", "+faststart",
                outputFile.absolutePath
            )
        ).toTypedArray()

        runFfmpeg(reencodeCommand, totalDurationMs, onSuccess = {
            statusText.text = "合并完成(已重新编码)，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("视频合并", outputFile.name, "成功(重新编码)")
            cleanup(localFiles, listFile)
        }, onFailure = { msg ->
            statusText.text = "合并失败: $msg"
            addHistory("视频合并", "多个文件", "失败")
            cleanup(localFiles, listFile)
        })
    }

    // ================= 3. 裁剪片段 =================

    private fun showTrimDialog(uri: Uri) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val startInput = EditText(this).apply { hint = "开始时间，如 00:00:10" }
        val endInput = EditText(this).apply { hint = "结束时间，如 00:01:30" }
        container.addView(startInput)
        container.addView(endInput)

        AlertDialog.Builder(this)
            .setTitle("裁剪片段(输入时分秒)")
            .setView(container)
            .setPositiveButton("开始裁剪") { _, _ ->
                val start = startInput.text.toString().ifBlank { "00:00:00" }
                val end = endInput.text.toString().trim()
                if (end.isBlank()) {
                    Toast.makeText(this, "请填写结束时间", Toast.LENGTH_SHORT).show()
                } else {
                    trimVideo(uri, start, end)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun trimVideo(uri: Uri, start: String, end: String) {
        val inputFile = copyUriToCache(uri, "trim_input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "trimmed_${System.currentTimeMillis()}.mp4")

        statusText.text = "裁剪中(无损快速)..."
        progressBar.visibility = ProgressBar.VISIBLE

        // 无损快速裁剪：-c copy，不重新编码
        val command = arrayOf(
            "-y", "-ss", start, "-to", end,
            "-i", inputFile.absolutePath, "-c", "copy", outputFile.absolutePath
        )

        runFfmpeg(command, onSuccess = {
            statusText.text = "裁剪完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("裁剪片段", outputFile.name, "成功")
            inputFile.delete()
        }, onFailure = {
            statusText.text = "快速裁剪失败，改用重新编码..."
            val command2 = arrayOf(
                "-y", "-ss", start, "-to", end, "-i", inputFile.absolutePath,
                "-c:v", "h264_mediacodec", "-b:v", "4M", "-c:a", "aac",
                outputFile.absolutePath
            )
            runFfmpeg(command2, onSuccess = {
                statusText.text = "裁剪完成(重新编码)，正在保存..."
                saveToDownloads(outputFile, "video/*")
                addHistory("裁剪片段", outputFile.name, "成功(重新编码)")
                inputFile.delete()
            }, onFailure = { msg ->
                statusText.text = "裁剪失败: $msg"
                addHistory("裁剪片段", uri.toString(), "失败")
                inputFile.delete()
            })
        })
    }

    // ================= 4. 压缩视频(含体积预估) =================

    private fun showCompressDialog(uris: List<Uri>) {
        // 先取第一个文件估算时长和分辨率，用于预估各档位输出体积
        val firstFile = copyUriToCache(uris[0], "probe_${System.currentTimeMillis()}")
        val durationSec = getDurationMs(firstFile.absolutePath) / 1000.0
        firstFile.delete()

        val levels = listOf(
            Triple("高清 (1080p, 6Mbps)", 1080, 6_000_000),
            Triple("中等 (720p, 3Mbps)", 720, 3_000_000),
            Triple("流畅 (480p, 1.2Mbps)", 480, 1_200_000)
        )

        val labels = levels.map { (name, _, bitrate) ->
            val estMB = if (durationSec > 0) (bitrate * durationSec / 8 / 1_000_000) else 0.0
            "$name  预估约${"%.1f".format(estMB)}MB/个"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择压缩清晰度(共${uris.size}个文件)")
            .setItems(labels) { _, which ->
                val (_, targetHeight, bitrate) = levels[which]
                compressBatch(uris, targetHeight, bitrate)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun compressBatch(uris: List<Uri>, targetHeight: Int, bitrate: Int, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部 ${uris.size} 个文件压缩完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            return
        }
        val inputFile = copyUriToCache(uris[index], "compress_input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "compressed_${System.currentTimeMillis()}.mp4")
        val durationMs = getDurationMs(inputFile.absolutePath)

        statusText.text = "[${index + 1}/${uris.size}] 压缩中... 0%"
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf(
            "-y", "-i", inputFile.absolutePath,
            "-vf", "scale=-2:$targetHeight",
            "-c:v", "h264_mediacodec", "-b:v", "${bitrate}",
            "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "44100", "-b:a", "128k",
            outputFile.absolutePath
        )

        runFfmpeg(command, durationMs, progressLabel = "[${index + 1}/${uris.size}] 压缩中", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 压缩完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("视频压缩", outputFile.name, "成功(${outputFile.length() / 1_000_000}MB)")
            inputFile.delete()
            compressBatch(uris, targetHeight, bitrate, index + 1)
        }, onFailure = { msg ->
            statusText.text = "[${index + 1}/${uris.size}] 压缩失败: $msg"
            addHistory("视频压缩", uris[index].toString(), "失败")
            inputFile.delete()
            compressBatch(uris, targetHeight, bitrate, index + 1)
        })
    }

    // ================= 5. 提取音频(批量) =================

    private fun extractAudioBatch(uris: List<Uri>, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部 ${uris.size} 个文件音频提取完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            return
        }
        val inputFile = copyUriToCache(uris[index], "audio_input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")

        statusText.text = "[${index + 1}/${uris.size}] 提取音频中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf("-y", "-i", inputFile.absolutePath, "-vn", "-c:a", "copy", outputFile.absolutePath)

        runFfmpeg(command, onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 提取成功，正在保存..."
            saveAudioToMusic(outputFile)
            addHistory("提取音频", outputFile.name, "成功(直接拷贝音轨)")
            inputFile.delete()
            extractAudioBatch(uris, index + 1)
        }, onFailure = {
            // 部分格式音轨不能直接拷贝(如需要转封装)，改用转码为aac
            val command2 = arrayOf("-y", "-i", inputFile.absolutePath, "-vn", "-c:a", "aac", "-b:a", "192k", outputFile.absolutePath)
            runFfmpeg(command2, onSuccess = {
                statusText.text = "[${index + 1}/${uris.size}] 提取成功(已转码)，正在保存..."
                saveAudioToMusic(outputFile)
                addHistory("提取音频", outputFile.name, "成功(转码)")
                inputFile.delete()
                extractAudioBatch(uris, index + 1)
            }, onFailure = { msg ->
                statusText.text = "[${index + 1}/${uris.size}] 提取失败: $msg"
                addHistory("提取音频", uris[index].toString(), "失败")
                inputFile.delete()
                extractAudioBatch(uris, index + 1)
            })
        })
    }

    // ================= 6. 视频转GIF =================

    private fun showGifDialog(uri: Uri) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val startInput = EditText(this).apply { hint = "开始时间(秒)，如 5" }
        val durationInput = EditText(this).apply { hint = "截取时长(秒)，如 3，建议不超过10秒" }
        container.addView(startInput)
        container.addView(durationInput)

        AlertDialog.Builder(this)
            .setTitle("视频转GIF")
            .setView(container)
            .setPositiveButton("生成") { _, _ ->
                val start = startInput.text.toString().ifBlank { "0" }
                val duration = durationInput.text.toString().ifBlank { "3" }
                convertToGif(uri, start, duration)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun convertToGif(uri: Uri, startSec: String, durationSec: String) {
        val inputFile = copyUriToCache(uri, "gif_input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "output_${System.currentTimeMillis()}.gif")

        statusText.text = "生成GIF中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf(
            "-y", "-ss", startSec, "-t", durationSec, "-i", inputFile.absolutePath,
            "-vf", "fps=10,scale=480:-1:flags=lanczos",
            outputFile.absolutePath
        )

        runFfmpeg(command, onSuccess = {
            statusText.text = "GIF生成完成，正在保存..."
            saveToDownloads(outputFile, "image/gif")
            addHistory("转GIF", outputFile.name, "成功")
            inputFile.delete()
        }, onFailure = { msg ->
            statusText.text = "GIF生成失败: $msg"
            addHistory("转GIF", uri.toString(), "失败")
            inputFile.delete()
        })
    }

    // ================= 7. 旋转/镜像 =================

    private fun showRotateDialog(uri: Uri) {
        val options = arrayOf("顺时针旋转90°", "逆时针旋转90°", "旋转180°", "水平镜像", "垂直镜像")
        AlertDialog.Builder(this)
            .setTitle("选择旋转/镜像方式")
            .setItems(options) { _, which -> rotateVideo(uri, which) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rotateVideo(uri: Uri, option: Int) {
        val inputFile = copyUriToCache(uri, "rotate_input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "rotated_${System.currentTimeMillis()}.mp4")

        statusText.text = "处理中..."
        progressBar.visibility = ProgressBar.VISIBLE

        when (option) {
            0, 1, 2 -> {
                // 90/180/270旋转：只写入metadata标记，不重新编码，速度极快
                // 绝大多数播放器(包括手机相册、微信等)都能正确识别这个标记方向播放
                val angle = when (option) { 0 -> "90"; 1 -> "270"; else -> "180" }
                val command = arrayOf(
                    "-y", "-i", inputFile.absolutePath, "-c", "copy",
                    "-metadata:s:v:0", "rotate=$angle",
                    outputFile.absolutePath
                )
                runFfmpeg(command, onSuccess = {
                    statusText.text = "旋转完成，正在保存..."
                    saveToDownloads(outputFile, "video/*")
                    addHistory("旋转视频", outputFile.name, "成功(快速)")
                    inputFile.delete()
                }, onFailure = { msg ->
                    statusText.text = "旋转失败: $msg"
                    addHistory("旋转视频", uri.toString(), "失败")
                    inputFile.delete()
                })
            }
            else -> {
                // 镜像必须重新编码，metadata标记做不到镜像效果
                val filter = if (option == 3) "hflip" else "vflip"
                val durationMs = getDurationMs(inputFile.absolutePath)
                val command = arrayOf(
                    "-y", "-i", inputFile.absolutePath, "-vf", filter,
                    "-c:v", "h264_mediacodec", "-b:v", "4M", "-c:a", "copy",
                    outputFile.absolutePath
                )
                runFfmpeg(command, durationMs, onSuccess = {
                    statusText.text = "镜像完成，正在保存..."
                    saveToDownloads(outputFile, "video/*")
                    addHistory("镜像视频", outputFile.name, "成功(重新编码)")
                    inputFile.delete()
                }, onFailure = { msg ->
                    statusText.text = "镜像失败: $msg"
                    addHistory("镜像视频", uri.toString(), "失败")
                    inputFile.delete()
                })
            }
        }
    }

    // ================= 8. 静音/替换音轨 =================

    private fun showAudioTrackDialog(uri: Uri) {
        val options = arrayOf("静音(去掉原声)", "替换为其他音频文件")
        AlertDialog.Builder(this)
            .setTitle("选择操作")
            .setItems(options) { _, which ->
                if (which == 0) {
                    muteVideo(uri)
                } else {
                    pendingVideoForAudioTrack = copyUriToCache(uri, "audiotrack_video_${System.currentTimeMillis()}")
                    pickAudioLauncher.launch(arrayOf("audio/*"))
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun muteVideo(uri: Uri) {
        val inputFile = copyUriToCache(uri, "mute_input_${System.currentTimeMillis()}")
        val outputFile = File(cacheDir, "muted_${System.currentTimeMillis()}.mp4")

        statusText.text = "静音处理中(快速)..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf("-y", "-i", inputFile.absolutePath, "-c", "copy", "-an", outputFile.absolutePath)

        runFfmpeg(command, onSuccess = {
            statusText.text = "静音完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("静音视频", outputFile.name, "成功")
            inputFile.delete()
        }, onFailure = { msg ->
            statusText.text = "静音失败: $msg"
            addHistory("静音视频", uri.toString(), "失败")
            inputFile.delete()
        })
    }

    private fun replaceAudioTrack(videoFile: File, audioFile: File) {
        val outputFile = File(cacheDir, "newaudio_${System.currentTimeMillis()}.mp4")

        statusText.text = "替换音轨中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf(
            "-y", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-shortest",
            outputFile.absolutePath
        )

        runFfmpeg(command, onSuccess = {
            statusText.text = "替换音轨完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("替换音轨", outputFile.name, "成功")
            videoFile.delete()
            audioFile.delete()
            pendingVideoForAudioTrack = null
        }, onFailure = { msg ->
            statusText.text = "替换音轨失败: $msg"
            addHistory("替换音轨", "失败", "失败")
            videoFile.delete()
            audioFile.delete()
            pendingVideoForAudioTrack = null
        })
    }

    // ================= 9. 查看视频信息 =================

    private fun showVideoInfo(uri: Uri) {
        statusText.text = "读取视频信息中..."
        val inputFile = copyUriToCache(uri, "info_${System.currentTimeMillis()}")

        val session = FFprobeKit.getMediaInformation(inputFile.absolutePath)
        val info = session?.mediaInformation

        val vStream = info?.streams?.firstOrNull { it.type == "video" }
        val aStream = info?.streams?.firstOrNull { it.type == "audio" }

        val durationSec = info?.duration?.toDoubleOrNull() ?: 0.0
        val fileSizeMB = inputFile.length() / 1_000_000.0

        val text = buildString {
            append("文件大小: ${"%.2f".format(fileSizeMB)} MB\n")
            append("时长: ${formatDuration(durationSec)}\n")
            append("容器格式: ${info?.format ?: "未知"}\n\n")
            append("【视频】\n")
            append("编码: ${vStream?.codec ?: "未知"}\n")
            append("分辨率: ${vStream?.width ?: "?"} x ${vStream?.height ?: "?"}\n")
            append("帧率: ${vStream?.averageFrameRate ?: "未知"}\n\n")
            append("【音频】\n")
            append("编码: ${aStream?.codec ?: "无音轨"}\n")
        }

        statusText.text = "选择一个操作开始"
        inputFile.delete()

        AlertDialog.Builder(this)
            .setTitle("视频信息")
            .setMessage(text)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun formatDuration(seconds: Double): String {
        val totalSec = seconds.toInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    // ================= 历史记录 =================

    private fun addHistory(action: String, fileName: String, status: String) {
        val time = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
        val entry = "[$time] $action - $fileName - $status"
        val existing = prefs.getString("log", "") ?: ""
        // 新记录放最前面，最多保留50条，避免无限增长
        val lines = (listOf(entry) + existing.split("\n").filter { it.isNotBlank() }).take(50)
        prefs.edit().putString("log", lines.joinToString("\n")).apply()
    }

    private fun showHistory() {
        val log = prefs.getString("log", "")
        val message = if (log.isNullOrBlank()) "暂无历史记录" else log

        AlertDialog.Builder(this)
            .setTitle("处理历史记录")
            .setMessage(message)
            .setPositiveButton("关闭", null)
            .setNegativeButton("清空记录") { _, _ ->
                prefs.edit().remove("log").apply()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ================= 通用ffmpeg执行封装 =================
    // 统一走这里：不做多余的事后校验，直接以ffmpeg返回码为准(和Termux手动操作的逻辑一致)

    private fun runFfmpeg(
        command: Array<String>,
        durationMs: Double = 0.0,
        progressLabel: String = "",
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val session = FFmpegKit.executeAsync(
            toCommandString(command),
            FFmpegSessionCompleteCallback { s ->
                runOnUiThread {
                    progressBar.visibility = ProgressBar.INVISIBLE
                    currentSession = null
                    if (ReturnCode.isSuccess(s.returnCode)) {
                        onSuccess()
                    } else if (ReturnCode.isCancel(s.returnCode)) {
                        statusText.text = "任务已取消"
                    } else {
                        val logs = try { s.allLogsAsString } catch (e: Exception) { "" }
                        onFailure(logs.takeLast(300))
                    }
                }
            },
            LogCallback { },
            StatisticsCallback { stats ->
                if (durationMs > 0) {
                    val percent = ((stats.time / durationMs) * 100).toInt().coerceIn(0, 100)
                    runOnUiThread {
                        progressBar.progress = percent
                        if (progressLabel.isNotBlank()) statusText.text = "$progressLabel... $percent%"
                    }
                }
            }
        )
        currentSession = session
    }

    // ================= 通用工具函数 =================

    private fun getDurationMs(path: String): Double {
        return try {
            val session = FFprobeKit.getMediaInformation(path)
            val durationStr = session.mediaInformation?.duration
            (durationStr?.toDoubleOrNull() ?: 0.0) * 1000
        } catch (e: Exception) {
            0.0
        }
    }

    private fun getVideoDimensions(path: String): Pair<Int, Int> {
        return try {
            val info = FFprobeKit.getMediaInformation(path)?.mediaInformation
            val vStream = info?.streams?.firstOrNull { it.type == "video" }
            val w = vStream?.width?.toString()?.toIntOrNull() ?: 1280
            val h = vStream?.height?.toString()?.toIntOrNull() ?: 720
            w to h
        } catch (e: Exception) {
            1280 to 720
        }
    }

    private fun copyUriToCache(uri: Uri, name: String): File {
        val file = File(cacheDir, name)
        contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun toCommandString(args: Array<String>): String =
        args.joinToString(" ") { arg -> if (arg.contains(" ")) "\"$arg\"" else arg }

    private fun cleanup(files: List<File>, listFile: File) {
        files.forEach { it.delete() }
        listFile.delete()
    }

    private fun saveToDownloads(file: File, mimeType: String) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                statusText.text = "已保存到 Downloads/${file.name}"
                Toast.makeText(this, "完成: ${file.name}", Toast.LENGTH_LONG).show()
            } ?: run { statusText.text = "保存失败：无法创建目标文件" }
        } catch (e: Exception) {
            statusText.text = "保存失败: ${e.message}"
        }
    }

    private fun saveAudioToMusic(file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC)
            }
            val uri = contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { out ->
                    file.inputStream().use { input -> input.copyTo(out) }
                }
                statusText.text = "已保存到 Music/${file.name}"
                Toast.makeText(this, "完成: ${file.name}", Toast.LENGTH_LONG).show()
            } ?: run { statusText.text = "保存失败：无法创建目标文件" }
        } catch (e: Exception) {
            statusText.text = "保存失败: ${e.message}"
        }
    }
}
