package com.example.videoconverter

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
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
import com.google.android.material.slider.RangeSlider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var pauseButtonRef: Button
    private lateinit var resumeButtonRef: Button

    private var currentSession: FFmpegSession? = null
    private lateinit var permissionHelper: PermissionHelper

    private enum class PendingAction {
        CONVERT, TRIM, COMPRESS, EXTRACT_AUDIO, GIF, ROTATE, MUTE,
        AUDIO_TRACK_VIDEO, INFO, CROP, WATERMARK, SUBTITLE, SPEED, THUMBNAIL
    }
    private var pendingAction: PendingAction? = null
    private var pendingVideoForAudioTrack: File? = null
    private var pendingVideoForSubtitle: File? = null

    // ---------- 文件选择器 ----------

    private val pickSingleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { onSingleFilePicked(it) } }

    private val pickMultipleLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) onMultipleFilesPicked(uris) }

    private val pickMergeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.size >= 2) showMergeModeDialog(uris)
        else if (uris.isNotEmpty()) statusText.text = "合并至少需要选择2个文件"
    }

    private val pickAudioLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { onAudioForTrackPicked(it) } }

    private val pickSrtLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { onSrtPicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        pauseButtonRef = findViewById(R.id.pauseButton)
        resumeButtonRef = findViewById(R.id.resumeButton)
        prefs = getSharedPreferences("app_history", Context.MODE_PRIVATE)

        // 启动时清理上次异常退出留下的临时文件
        StorageUtil.cleanStaleCache(this)

        permissionHelper = PermissionHelper(this) { }
        permissionHelper.requestNotificationPermissionIfNeeded(this)

        findViewById<Button>(R.id.pickButton).setOnClickListener {
            pendingAction = PendingAction.CONVERT
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.mergeButton).setOnClickListener {
            pickMergeLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.trimButton).setOnClickListener {
            pendingAction = PendingAction.TRIM
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.cropButton).setOnClickListener {
            pendingAction = PendingAction.CROP
            pickMultipleLauncher.launch(arrayOf("video/*"))
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
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.rotateButton).setOnClickListener {
            pendingAction = PendingAction.ROTATE
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.muteButton).setOnClickListener {
            pendingAction = PendingAction.MUTE
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.audioTrackButton).setOnClickListener {
            pendingAction = PendingAction.AUDIO_TRACK_VIDEO
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.watermarkButton).setOnClickListener {
            pendingAction = PendingAction.WATERMARK
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.subtitleButton).setOnClickListener {
            pendingAction = PendingAction.SUBTITLE
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.speedButton).setOnClickListener {
            pendingAction = PendingAction.SPEED
            pickMultipleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.thumbnailButton).setOnClickListener {
            pendingAction = PendingAction.THUMBNAIL
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.infoButton).setOnClickListener {
            pendingAction = PendingAction.INFO
            pickSingleLauncher.launch(arrayOf("video/*"))
        }

        findViewById<Button>(R.id.historyButton).setOnClickListener { showHistory() }

        findViewById<Button>(R.id.clearCacheButton).setOnClickListener { showClearCacheDialog() }

        findViewById<Button>(R.id.pauseButton).setOnClickListener { pauseCurrentTask() }
        findViewById<Button>(R.id.resumeButton).setOnClickListener { resumeCurrentTask() }

        findViewById<Button>(R.id.cancelButton).setOnClickListener {
            currentSession?.let {
                FFmpegKit.cancel(it.sessionId)
                pauseState = null
                hidePauseResumeButtons()
                statusText.text = "已取消当前任务"
                progressBar.visibility = ProgressBar.INVISIBLE
                stopProcessingNotice()
            } ?: run {
                Toast.makeText(this, "当前没有正在进行的任务", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- 分发选择结果 ----------

    private fun onSingleFilePicked(uri: Uri) {
        when (pendingAction) {
            PendingAction.AUDIO_TRACK_VIDEO -> showAudioTrackDialog(uri)
            PendingAction.INFO -> showVideoInfo(uri)
            PendingAction.THUMBNAIL -> showThumbnailPreview(uri)
            PendingAction.SUBTITLE -> {
                statusText.text = "正在准备..."
                pendingVideoForSubtitle = copyUriToCache(uri, "subtitle_video_${System.currentTimeMillis()}")
                pickSrtLauncher.launch(arrayOf("*/*"))
            }
            else -> {}
        }
    }

    private fun onMultipleFilesPicked(uris: List<Uri>) {
        when (pendingAction) {
            PendingAction.CONVERT -> showFormatDialog(uris)
            PendingAction.COMPRESS -> showCompressDialog(uris)
            PendingAction.EXTRACT_AUDIO -> extractAudioBatch(uris)
            PendingAction.TRIM -> showTrimDialog(uris)
            PendingAction.GIF -> showGifDialog(uris)
            PendingAction.ROTATE -> showRotateDialog(uris)
            PendingAction.MUTE -> muteBatch(uris)
            PendingAction.CROP -> showCropDialog(uris)
            PendingAction.WATERMARK -> showWatermarkDialog(uris)
            PendingAction.SPEED -> showSpeedDialog(uris)
            else -> {}
        }
    }

    private fun onAudioForTrackPicked(audioUri: Uri) {
        val videoFile = pendingVideoForAudioTrack ?: return
        val audioFile = copyUriToCache(audioUri, "audiotrack_${System.currentTimeMillis()}")
        replaceAudioTrack(videoFile, audioFile)
    }

    private fun onSrtPicked(srtUri: Uri) {
        val videoFile = pendingVideoForSubtitle ?: return
        val srtFile = File(cacheDir, "subtitle_${System.currentTimeMillis()}.srt")
        contentResolver.openInputStream(srtUri)?.use { input ->
            srtFile.outputStream().use { output -> input.copyTo(output) }
        }
        burnSubtitle(videoFile, srtFile)
    }

    // ================= 1. 转换格式(批量 + 高级选项) =================

    private fun showFormatDialog(uris: List<Uri>) {
        val formats = arrayOf("mp4", "mkv", "webm")
        AlertDialog.Builder(this)
            .setTitle("选择输出格式(共${uris.size}个文件)")
            .setItems(formats) { _, which -> showAdvancedEncodeOptions(uris, formats[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAdvancedEncodeOptions(uris: List<Uri>, targetFormat: String) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val bitrateInput = EditText(this).apply { hint = "码率kbps，留空=自动(仅重新编码时生效)" }
        val fpsInput = EditText(this).apply { hint = "帧率fps，留空=保持原帧率" }
        container.addView(bitrateInput); container.addView(fpsInput)
        AlertDialog.Builder(this)
            .setTitle("高级选项(可选，可留空)")
            .setView(container)
            .setPositiveButton("开始转换") { _, _ ->
                val bitrate = bitrateInput.text.toString().trim().toIntOrNull()
                val fps = fpsInput.text.toString().trim().toIntOrNull()
                convertBatch(uris, targetFormat, bitrate, fps)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun convertBatch(
        uris: List<Uri>, targetFormat: String,
        customBitrateKbps: Int? = null, customFps: Int? = null, index: Int = 0
    ) {
        if (index >= uris.size) {
            statusText.text = "全部 ${uris.size} 个文件转换完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("格式转换")
        val inputFile = copyUriToCache(uris[index], "input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val outputFile = File(cacheDir, "converted_${System.currentTimeMillis()}.$targetFormat")

        statusText.text = "[${index + 1}/${uris.size}] 先尝试快速换容器(不重新编码)..."
        progressBar.visibility = ProgressBar.VISIBLE

        val remuxCommand = arrayOf("-y", "-i", inputFile.absolutePath, "-c", "copy", outputFile.absolutePath)

        runFfmpeg(remuxCommand, noticeTitle = "格式转换", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 快速换壳成功，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("格式转换", outputFile.name, "成功(快速换壳)")
            inputFile.delete()
            convertBatch(uris, targetFormat, customBitrateKbps, customFps, index + 1)
        }, onFailure = {
            statusText.text = "[${index + 1}/${uris.size}] 该格式不能直接换壳，改用重新编码..."
            val durationMs = getDurationMs(inputFile.absolutePath)
            val filterArgs = mutableListOf(
                "-c:v", "h264_mediacodec", "-b:v", customBitrateKbps?.let { "${it}k" } ?: "4M",
                "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-ar", "44100"
            )
            if (customFps != null) { filterArgs.add("-r"); filterArgs.add("$customFps") }
            runResumableEncode(
                taskLabel = "转换[${index + 1}/${uris.size}]",
                inputPath = inputFile.absolutePath,
                filterAndCodecArgs = filterArgs,
                outputExt = targetFormat,
                totalDurationMs = durationMs,
                onFinalSuccess = { finalFile ->
                    statusText.text = "[${index + 1}/${uris.size}] 转换完成，正在保存..."
                    saveToDownloads(finalFile, "video/*")
                    addHistory("格式转换", finalFile.name, "成功(重新编码)")
                    inputFile.delete()
                    convertBatch(uris, targetFormat, customBitrateKbps, customFps, index + 1)
                },
                onFinalFailure = { msg ->
                    statusText.text = "[${index + 1}/${uris.size}] 转换失败: ${FfmpegError.friendly(msg)}"
                    addHistory("格式转换", uris[index].toString(), "失败")
                    inputFile.delete()
                    convertBatch(uris, targetFormat, customBitrateKbps, customFps, index + 1)
                }
            )
        })
    }

    // ================= 2. 合并视频(可选转场) =================

    private fun showMergeModeDialog(uris: List<Uri>) {
        val options = arrayOf("快速拼接(推荐，无转场)", "淡入淡出交叉转场(重新编码，较慢)")
        AlertDialog.Builder(this)
            .setTitle("选择合并方式(共${uris.size}个文件)")
            .setItems(options) { _, which ->
                if (which == 0) mergeVideos(uris) else showTransitionDurationDialog(uris)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showTransitionDurationDialog(uris: List<Uri>) {
        val input = EditText(this).apply { hint = "转场时长(秒)，如 1"; setText("1") }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("转场时长")
            .setView(container)
            .setPositiveButton("开始合并") { _, _ ->
                val t = input.text.toString().trim().toDoubleOrNull() ?: 1.0
                mergeWithTransition(uris, t)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun mergeVideos(uris: List<Uri>) {
        statusText.text = "准备 ${uris.size} 个文件..."
        progressBar.visibility = ProgressBar.VISIBLE
        startProcessingNotice("视频合并")

        val localFiles = uris.mapIndexed { index, uri -> copyUriToCache(uri, "merge_${index}_${System.currentTimeMillis()}") }
        if (!checkStorageOrWarn(localFiles.sumOf { it.length() })) { localFiles.forEach { it.delete() }; return }

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

        runFfmpeg(copyCommand, noticeTitle = "视频合并", onSuccess = {
            statusText.text = "合并完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("视频合并", outputFile.name, "成功(快速拼接)")
            cleanup(localFiles, listFile)
            stopProcessingNotice()
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

        runFfmpeg(reencodeCommand, totalDurationMs, progressLabel = "合并中", noticeTitle = "视频合并", onSuccess = {
            statusText.text = "合并完成(已重新编码)，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("视频合并", outputFile.name, "成功(重新编码)")
            cleanup(localFiles, listFile)
            stopProcessingNotice()
        }, onFailure = { msg ->
            statusText.text = "合并失败: ${FfmpegError.friendly(msg)}"
            addHistory("视频合并", "多个文件", "失败")
            cleanup(localFiles, listFile)
            stopProcessingNotice()
        })
    }

    private fun mergeWithTransition(uris: List<Uri>, transitionSec: Double) {
        if (uris.size < 2) { statusText.text = "转场合并至少需要2个文件"; return }
        statusText.text = "准备转场合并..."
        progressBar.visibility = ProgressBar.VISIBLE
        startProcessingNotice("转场合并")

        val localFiles = uris.mapIndexed { i, u -> copyUriToCache(u, "xfade_${i}_${System.currentTimeMillis()}") }
        if (!checkStorageOrWarn(localFiles.sumOf { it.length() })) { localFiles.forEach { it.delete() }; return }

        val durations = localFiles.map { (getDurationMs(it.absolutePath) / 1000.0).coerceAtLeast(0.5) }
        val dims = localFiles.map { getVideoDimensions(it.absolutePath) }
        val targetWidth = dims.maxOf { it.first }.let { if (it % 2 != 0) it + 1 else it }
        val targetHeight = dims.maxOf { it.second }.let { if (it % 2 != 0) it + 1 else it }
        val n = localFiles.size
        val t = transitionSec.coerceIn(0.2, (durations.minOrNull() ?: 1.0) * 0.8)

        val steps = mutableListOf<String>()
        for (i in 0 until n) {
            steps.add("[$i:v:0]scale=$targetWidth:$targetHeight:force_original_aspect_ratio=decrease,pad=$targetWidth:$targetHeight:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p[v$i]")
        }
        var lastLabel = "v0"
        var accDuration = durations[0]
        for (i in 1 until n) {
            val offset = accDuration - t * i
            val outLabel = if (i == n - 1) "vout" else "vx$i"
            steps.add("[$lastLabel][v$i]xfade=transition=fade:duration=${"%.3f".format(t)}:offset=${"%.3f".format(offset.coerceAtLeast(0.0))}[$outLabel]")
            lastLabel = outLabel
            accDuration += durations[i]
        }
        var lastA = "0:a:0"
        for (i in 1 until n) {
            val outLabel = if (i == n - 1) "aout" else "ax$i"
            steps.add("[$lastA][$i:a:0]acrossfade=d=${"%.3f".format(t)}[$outLabel]")
            lastA = outLabel
        }
        val filterComplex = steps.joinToString(";")
        val estTotalMs = (accDuration - (n - 1) * t) * 1000

        val inputArgs = localFiles.flatMap { listOf("-i", it.absolutePath) }
        val outputFile = File(cacheDir, "merged_xfade_${System.currentTimeMillis()}.mp4")
        val command = (listOf("-y") + inputArgs + listOf(
            "-filter_complex", filterComplex,
            "-map", "[vout]", "-map", "[aout]",
            "-c:v", "h264_mediacodec", "-b:v", "4M",
            "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "44100", "-movflags", "+faststart",
            outputFile.absolutePath
        )).toTypedArray()

        statusText.text = "转场合并中(需重新编码，请耐心等待)..."
        runFfmpeg(command, estTotalMs, progressLabel = "转场合并中", noticeTitle = "转场合并", onSuccess = {
            statusText.text = "转场合并完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("视频合并", outputFile.name, "成功(转场)")
            localFiles.forEach { it.delete() }
            stopProcessingNotice()
        }, onFailure = { msg ->
            statusText.text = "转场合并失败: ${FfmpegError.friendly(msg)} (提示: 所有片段都需要包含音轨才能使用转场)"
            addHistory("视频合并", "多个文件", "失败(转场)")
            localFiles.forEach { it.delete() }
            stopProcessingNotice()
        })
    }

    // ================= 3. 裁剪片段(拖拽选区，批量) =================

    private fun showTrimDialog(uris: List<Uri>) {
        statusText.text = "读取视频信息..."
        val firstFile = copyUriToCache(uris[0], "trim_probe_${System.currentTimeMillis()}")
        val durationSec = (getDurationMs(firstFile.absolutePath) / 1000.0).coerceAtLeast(1.0)

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val rangeLabel = TextView(this)
        val slider = RangeSlider(this).apply {
            valueFrom = 0f
            valueTo = durationSec.toFloat()
            values = listOf(0f, durationSec.toFloat())
        }
        fun updateLabel() {
            val vals = slider.values
            rangeLabel.text = "起点 ${formatDuration(vals[0].toDouble())}  —  终点 ${formatDuration(vals[1].toDouble())}"
        }
        updateLabel()
        slider.addOnChangeListener { _, _, _ -> updateLabel() }
        container.addView(rangeLabel)
        container.addView(slider)

        val thumbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val thumbScroll = HorizontalScrollView(this).apply { addView(thumbRow) }
        container.addView(thumbScroll)
        loadThumbnailsInto(firstFile.absolutePath, thumbRow)

        if (uris.size > 1) {
            container.addView(TextView(this).apply { text = "注意: 将对全部${uris.size}个文件使用相同的起止时间" })
        }

        AlertDialog.Builder(this)
            .setTitle("裁剪片段(拖动两端选择范围)")
            .setView(container)
            .setPositiveButton("开始裁剪") { _, _ ->
                val vals = slider.values
                val start = formatDuration(vals[0].toDouble())
                val end = formatDuration(vals[1].toDouble())
                trimBatch(uris, firstFile, start, end)
            }
            .setNegativeButton("取消") { _, _ -> firstFile.delete() }
            .show()
    }

    private fun trimBatch(uris: List<Uri>, preCopiedFirst: File?, start: String, end: String, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部裁剪完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("裁剪片段")
        val inputFile = if (index == 0 && preCopiedFirst != null) preCopiedFirst
            else copyUriToCache(uris[index], "trim_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val outputFile = File(cacheDir, "trimmed_${System.currentTimeMillis()}.mp4")

        statusText.text = "[${index + 1}/${uris.size}] 裁剪中(无损快速)..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf("-y", "-ss", start, "-to", end, "-i", inputFile.absolutePath, "-c", "copy", outputFile.absolutePath)

        runFfmpeg(command, noticeTitle = "裁剪片段", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 裁剪完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("裁剪片段", outputFile.name, "成功")
            inputFile.delete()
            trimBatch(uris, null, start, end, index + 1)
        }, onFailure = {
            statusText.text = "[${index + 1}/${uris.size}] 快速裁剪失败，改用重新编码..."
            val durationMs = getDurationMs(inputFile.absolutePath)
            val command2 = arrayOf(
                "-y", "-ss", start, "-to", end, "-i", inputFile.absolutePath,
                "-c:v", "h264_mediacodec", "-b:v", "4M", "-c:a", "aac",
                outputFile.absolutePath
            )
            runFfmpeg(command2, durationMs, progressLabel = "[${index + 1}/${uris.size}]裁剪中", noticeTitle = "裁剪片段", onSuccess = {
                statusText.text = "[${index + 1}/${uris.size}] 裁剪完成(重新编码)，正在保存..."
                saveToDownloads(outputFile, "video/*")
                addHistory("裁剪片段", outputFile.name, "成功(重新编码)")
                inputFile.delete()
                trimBatch(uris, null, start, end, index + 1)
            }, onFailure = { msg ->
                statusText.text = "[${index + 1}/${uris.size}] 裁剪失败: ${FfmpegError.friendly(msg)}"
                addHistory("裁剪片段", uris[index].toString(), "失败")
                inputFile.delete()
                trimBatch(uris, null, start, end, index + 1)
            })
        })
    }

    // ================= 3b. 自定义分辨率裁切 =================

    private fun showCropDialog(uris: List<Uri>) {
        val firstFile = copyUriToCache(uris[0], "crop_probe_${System.currentTimeMillis()}")
        val (srcW, srcH) = getVideoDimensions(firstFile.absolutePath)
        firstFile.delete()

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val info = TextView(this).apply { text = "源分辨率参考(第1个文件): ${srcW}x${srcH}" }
        val wInput = EditText(this).apply { hint = "裁切宽度px"; setText("$srcW") }
        val hInput = EditText(this).apply { hint = "裁切高度px"; setText("$srcH") }
        val xInput = EditText(this).apply { hint = "起始x坐标"; setText("0") }
        val yInput = EditText(this).apply { hint = "起始y坐标"; setText("0") }
        val bitrateInput = EditText(this).apply { hint = "码率kbps，留空=4000" }
        val fpsInput = EditText(this).apply { hint = "帧率fps，留空=保持原帧率" }
        listOf(info, wInput, hInput, xInput, yInput, bitrateInput, fpsInput).forEach { container.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("裁切画面(共${uris.size}个文件)")
            .setView(container)
            .setPositiveButton("开始裁切") { _, _ ->
                var w = wInput.text.toString().trim().toIntOrNull() ?: srcW
                var h = hInput.text.toString().trim().toIntOrNull() ?: srcH
                if (w % 2 != 0) w -= 1
                if (h % 2 != 0) h -= 1
                val x = xInput.text.toString().trim().toIntOrNull() ?: 0
                val y = yInput.text.toString().trim().toIntOrNull() ?: 0
                val bitrate = (bitrateInput.text.toString().trim().toIntOrNull() ?: 4000) * 1000
                val fps = fpsInput.text.toString().trim().toIntOrNull()
                cropBatch(uris, w, h, x, y, bitrate, fps)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun cropBatch(uris: List<Uri>, w: Int, h: Int, x: Int, y: Int, bitrate: Int, fps: Int?, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部裁切完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("裁切画面")
        val inputFile = copyUriToCache(uris[index], "crop_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val durationMs = getDurationMs(inputFile.absolutePath)
        val outputFile = File(cacheDir, "cropped_${System.currentTimeMillis()}.mp4")

        statusText.text = "[${index + 1}/${uris.size}] 裁切中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val args = mutableListOf(
            "-y", "-i", inputFile.absolutePath,
            "-vf", "crop=$w:$h:$x:$y",
            "-c:v", "h264_mediacodec", "-b:v", "$bitrate",
            "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "44100"
        )
        if (fps != null) { args.add("-r"); args.add("$fps") }
        args.add(outputFile.absolutePath)

        runFfmpeg(args.toTypedArray(), durationMs, progressLabel = "[${index + 1}/${uris.size}]裁切中", noticeTitle = "裁切画面", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 裁切完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("裁切画面", outputFile.name, "成功")
            inputFile.delete()
            cropBatch(uris, w, h, x, y, bitrate, fps, index + 1)
        }, onFailure = { msg ->
            statusText.text = "[${index + 1}/${uris.size}] 裁切失败: ${FfmpegError.friendly(msg)}"
            addHistory("裁切画面", uris[index].toString(), "失败")
            inputFile.delete()
            cropBatch(uris, w, h, x, y, bitrate, fps, index + 1)
        })
    }

    // ================= 4. 压缩视频(含自定义参数、暂停恢复) =================

    private fun showCompressDialog(uris: List<Uri>) {
        val firstFile = copyUriToCache(uris[0], "probe_${System.currentTimeMillis()}")
        val durationSec = getDurationMs(firstFile.absolutePath) / 1000.0
        firstFile.delete()

        val levels = listOf(
            Triple("高清 (1080p, 6Mbps)", 1080, 6_000_000),
            Triple("中等 (720p, 3Mbps)", 720, 3_000_000),
            Triple("流畅 (480p, 1.2Mbps)", 480, 1_200_000)
        )

        val labels = (levels.map { (name, _, bitrate) ->
            val estMB = if (durationSec > 0) (bitrate * durationSec / 8 / 1_000_000) else 0.0
            "$name  预估约${"%.1f".format(estMB)}MB/个"
        } + listOf("自定义分辨率/码率/帧率")).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("选择压缩清晰度(共${uris.size}个文件)")
            .setItems(labels) { _, which ->
                if (which < levels.size) {
                    val (_, targetHeight, bitrate) = levels[which]
                    compressBatch(uris, targetHeight, bitrate)
                } else {
                    showCustomCompressDialog(uris)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showCustomCompressDialog(uris: List<Uri>) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val heightInput = EditText(this).apply { hint = "目标高度px，如 720"; setText("720") }
        val bitrateInput = EditText(this).apply { hint = "码率kbps，如 3000"; setText("3000") }
        val fpsInput = EditText(this).apply { hint = "帧率fps，留空=保持原帧率" }
        container.addView(heightInput); container.addView(bitrateInput); container.addView(fpsInput)
        AlertDialog.Builder(this)
            .setTitle("自定义压缩参数")
            .setView(container)
            .setPositiveButton("开始压缩") { _, _ ->
                val h = heightInput.text.toString().trim().toIntOrNull() ?: 720
                val br = (bitrateInput.text.toString().trim().toIntOrNull() ?: 3000) * 1000
                val fps = fpsInput.text.toString().trim().toIntOrNull()
                compressBatch(uris, h, br, fps)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun compressBatch(uris: List<Uri>, targetHeight: Int, bitrate: Int, customFps: Int? = null, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部 ${uris.size} 个文件压缩完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("视频压缩")
        val inputFile = copyUriToCache(uris[index], "compress_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val durationMs = getDurationMs(inputFile.absolutePath)

        statusText.text = "[${index + 1}/${uris.size}] 压缩中... 0%"
        progressBar.visibility = ProgressBar.VISIBLE

        val filterArgs = mutableListOf(
            "-vf", "scale=-2:$targetHeight",
            "-c:v", "h264_mediacodec", "-b:v", "$bitrate",
            "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "44100", "-b:a", "128k"
        )
        if (customFps != null) { filterArgs.add("-r"); filterArgs.add("$customFps") }

        runResumableEncode(
            taskLabel = "压缩[${index + 1}/${uris.size}]",
            inputPath = inputFile.absolutePath,
            filterAndCodecArgs = filterArgs,
            outputExt = "mp4",
            totalDurationMs = durationMs,
            onFinalSuccess = { finalFile ->
                statusText.text = "[${index + 1}/${uris.size}] 压缩完成，正在保存..."
                saveToDownloads(finalFile, "video/*")
                addHistory("视频压缩", finalFile.name, "成功(${finalFile.length() / 1_000_000}MB)")
                inputFile.delete()
                compressBatch(uris, targetHeight, bitrate, customFps, index + 1)
            },
            onFinalFailure = { msg ->
                statusText.text = "[${index + 1}/${uris.size}] 压缩失败: ${FfmpegError.friendly(msg)}"
                addHistory("视频压缩", uris[index].toString(), "失败")
                inputFile.delete()
                compressBatch(uris, targetHeight, bitrate, customFps, index + 1)
            }
        )
    }

    // ================= 5. 提取音频(批量) =================

    private fun extractAudioBatch(uris: List<Uri>, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部 ${uris.size} 个文件音频提取完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("提取音频")
        val inputFile = copyUriToCache(uris[index], "audio_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val outputFile = File(cacheDir, "audio_${System.currentTimeMillis()}.m4a")

        statusText.text = "[${index + 1}/${uris.size}] 提取音频中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf("-y", "-i", inputFile.absolutePath, "-vn", "-c:a", "copy", outputFile.absolutePath)

        runFfmpeg(command, noticeTitle = "提取音频", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 提取成功，正在保存..."
            saveAudioToMusic(outputFile)
            addHistory("提取音频", outputFile.name, "成功(直接拷贝音轨)")
            inputFile.delete()
            extractAudioBatch(uris, index + 1)
        }, onFailure = {
            val command2 = arrayOf("-y", "-i", inputFile.absolutePath, "-vn", "-c:a", "aac", "-b:a", "192k", outputFile.absolutePath)
            runFfmpeg(command2, noticeTitle = "提取音频", onSuccess = {
                statusText.text = "[${index + 1}/${uris.size}] 提取成功(已转码)，正在保存..."
                saveAudioToMusic(outputFile)
                addHistory("提取音频", outputFile.name, "成功(转码)")
                inputFile.delete()
                extractAudioBatch(uris, index + 1)
            }, onFailure = { msg ->
                statusText.text = "[${index + 1}/${uris.size}] 提取失败: ${FfmpegError.friendly(msg)}"
                addHistory("提取音频", uris[index].toString(), "失败")
                inputFile.delete()
                extractAudioBatch(uris, index + 1)
            })
        })
    }

    // ================= 6. 视频转GIF(拖拽选区，批量) =================

    private fun showGifDialog(uris: List<Uri>) {
        statusText.text = "读取视频信息..."
        val firstFile = copyUriToCache(uris[0], "gif_probe_${System.currentTimeMillis()}")
        val durationSec = (getDurationMs(firstFile.absolutePath) / 1000.0).coerceAtLeast(1.0)

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val rangeLabel = TextView(this)
        val defaultEnd = minOf(3.0, durationSec).toFloat()
        val slider = RangeSlider(this).apply {
            valueFrom = 0f
            valueTo = durationSec.toFloat()
            values = listOf(0f, defaultEnd)
        }
        fun updateLabel() {
            val vals = slider.values
            rangeLabel.text = "开始 ${"%.1f".format(vals[0])}s  时长 ${"%.1f".format(vals[1] - vals[0])}s (建议不超过10秒)"
        }
        updateLabel()
        slider.addOnChangeListener { _, _, _ -> updateLabel() }
        container.addView(rangeLabel)
        container.addView(slider)

        val thumbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val thumbScroll = HorizontalScrollView(this).apply { addView(thumbRow) }
        container.addView(thumbScroll)
        loadThumbnailsInto(firstFile.absolutePath, thumbRow)

        AlertDialog.Builder(this)
            .setTitle("视频转GIF(拖动选择片段)")
            .setView(container)
            .setPositiveButton("生成") { _, _ ->
                val vals = slider.values
                val start = vals[0].toString()
                val dur = (vals[1] - vals[0]).coerceAtLeast(0.2f).toString()
                gifBatch(uris, firstFile, start, dur)
            }
            .setNegativeButton("取消") { _, _ -> firstFile.delete() }
            .show()
    }

    private fun gifBatch(uris: List<Uri>, preCopiedFirst: File?, startSec: String, durationSec: String, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部GIF生成完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("转GIF")
        val inputFile = if (index == 0 && preCopiedFirst != null) preCopiedFirst
            else copyUriToCache(uris[index], "gif_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val outputFile = File(cacheDir, "output_${System.currentTimeMillis()}.gif")

        statusText.text = "[${index + 1}/${uris.size}] 生成GIF中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf(
            "-y", "-ss", startSec, "-t", durationSec, "-i", inputFile.absolutePath,
            "-vf", "fps=10,scale=480:-1:flags=lanczos",
            outputFile.absolutePath
        )

        runFfmpeg(command, noticeTitle = "转GIF", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] GIF生成完成，正在保存..."
            saveToDownloads(outputFile, "image/gif")
            addHistory("转GIF", outputFile.name, "成功")
            inputFile.delete()
            gifBatch(uris, null, startSec, durationSec, index + 1)
        }, onFailure = { msg ->
            statusText.text = "[${index + 1}/${uris.size}] GIF生成失败: ${FfmpegError.friendly(msg)}"
            addHistory("转GIF", uris[index].toString(), "失败")
            inputFile.delete()
            gifBatch(uris, null, startSec, durationSec, index + 1)
        })
    }

    // ================= 7. 旋转/镜像(批量) =================

    private fun showRotateDialog(uris: List<Uri>) {
        val options = arrayOf("顺时针旋转90°", "逆时针旋转90°", "旋转180°", "水平镜像", "垂直镜像")
        AlertDialog.Builder(this)
            .setTitle("选择旋转/镜像方式(共${uris.size}个文件)")
            .setItems(options) { _, which -> rotateBatch(uris, which) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun rotateBatch(uris: List<Uri>, option: Int, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部处理完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("旋转/镜像")
        val inputFile = copyUriToCache(uris[index], "rotate_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val outputFile = File(cacheDir, "rotated_${System.currentTimeMillis()}.mp4")

        statusText.text = "[${index + 1}/${uris.size}] 处理中..."
        progressBar.visibility = ProgressBar.VISIBLE

        when (option) {
            0, 1, 2 -> {
                val angle = when (option) { 0 -> "90"; 1 -> "270"; else -> "180" }
                val command = arrayOf(
                    "-y", "-i", inputFile.absolutePath, "-c", "copy",
                    "-metadata:s:v:0", "rotate=$angle",
                    outputFile.absolutePath
                )
                runFfmpeg(command, noticeTitle = "旋转", onSuccess = {
                    statusText.text = "[${index + 1}/${uris.size}] 旋转完成，正在保存..."
                    saveToDownloads(outputFile, "video/*")
                    addHistory("旋转视频", outputFile.name, "成功(快速)")
                    inputFile.delete()
                    rotateBatch(uris, option, index + 1)
                }, onFailure = { msg ->
                    statusText.text = "[${index + 1}/${uris.size}] 旋转失败: ${FfmpegError.friendly(msg)}"
                    addHistory("旋转视频", uris[index].toString(), "失败")
                    inputFile.delete()
                    rotateBatch(uris, option, index + 1)
                })
            }
            else -> {
                val filter = if (option == 3) "hflip" else "vflip"
                val durationMs = getDurationMs(inputFile.absolutePath)
                val command = arrayOf(
                    "-y", "-i", inputFile.absolutePath, "-vf", filter,
                    "-c:v", "h264_mediacodec", "-b:v", "4M", "-c:a", "copy",
                    outputFile.absolutePath
                )
                runFfmpeg(command, durationMs, progressLabel = "[${index + 1}/${uris.size}]镜像中", noticeTitle = "镜像", onSuccess = {
                    statusText.text = "[${index + 1}/${uris.size}] 镜像完成，正在保存..."
                    saveToDownloads(outputFile, "video/*")
                    addHistory("镜像视频", outputFile.name, "成功(重新编码)")
                    inputFile.delete()
                    rotateBatch(uris, option, index + 1)
                }, onFailure = { msg ->
                    statusText.text = "[${index + 1}/${uris.size}] 镜像失败: ${FfmpegError.friendly(msg)}"
                    addHistory("镜像视频", uris[index].toString(), "失败")
                    inputFile.delete()
                    rotateBatch(uris, option, index + 1)
                })
            }
        }
    }

    // ================= 8. 静音(批量) / 替换音轨(单个) =================

    private fun muteBatch(uris: List<Uri>, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部静音处理完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("静音视频")
        val inputFile = copyUriToCache(uris[index], "mute_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val outputFile = File(cacheDir, "muted_${System.currentTimeMillis()}.mp4")

        statusText.text = "[${index + 1}/${uris.size}] 静音处理中(快速)..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf("-y", "-i", inputFile.absolutePath, "-c", "copy", "-an", outputFile.absolutePath)

        runFfmpeg(command, noticeTitle = "静音视频", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 静音完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("静音视频", outputFile.name, "成功")
            inputFile.delete()
            muteBatch(uris, index + 1)
        }, onFailure = { msg ->
            statusText.text = "[${index + 1}/${uris.size}] 静音失败: ${FfmpegError.friendly(msg)}"
            addHistory("静音视频", uris[index].toString(), "失败")
            inputFile.delete()
            muteBatch(uris, index + 1)
        })
    }

    private fun showAudioTrackDialog(uri: Uri) {
        pendingVideoForAudioTrack = copyUriToCache(uri, "audiotrack_video_${System.currentTimeMillis()}")
        pickAudioLauncher.launch(arrayOf("audio/*"))
    }

    private fun replaceAudioTrack(videoFile: File, audioFile: File) {
        val outputFile = File(cacheDir, "newaudio_${System.currentTimeMillis()}.mp4")

        statusText.text = "替换音轨中..."
        progressBar.visibility = ProgressBar.VISIBLE
        startProcessingNotice("替换音轨")

        val command = arrayOf(
            "-y", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-shortest",
            outputFile.absolutePath
        )

        runFfmpeg(command, noticeTitle = "替换音轨", onSuccess = {
            statusText.text = "替换音轨完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("替换音轨", outputFile.name, "成功")
            videoFile.delete()
            audioFile.delete()
            pendingVideoForAudioTrack = null
            stopProcessingNotice()
        }, onFailure = { msg ->
            statusText.text = "替换音轨失败: ${FfmpegError.friendly(msg)}"
            addHistory("替换音轨", "失败", "失败")
            videoFile.delete()
            audioFile.delete()
            pendingVideoForAudioTrack = null
            stopProcessingNotice()
        })
    }

    // ================= 9. 水印(批量) =================

    private fun showWatermarkDialog(uris: List<Uri>) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val textInput = EditText(this).apply { hint = "水印文字"; setText("我的视频") }
        val positions = arrayOf("右下角", "左下角", "右上角", "左上角", "居中")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, positions)
        }
        val sizeInput = EditText(this).apply { hint = "字体大小，默认24"; setText("24") }
        val opacityInput = EditText(this).apply { hint = "不透明度(0-100)，默认80"; setText("80") }
        listOf(textInput, spinner, sizeInput, opacityInput).forEach { container.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("添加文字水印(共${uris.size}个文件)")
            .setView(container)
            .setPositiveButton("开始添加") { _, _ ->
                val text = textInput.text.toString().ifBlank { "watermark" }
                val position = spinner.selectedItemPosition
                val size = sizeInput.text.toString().trim().toIntOrNull() ?: 24
                val opacity = (opacityInput.text.toString().trim().toIntOrNull() ?: 80).coerceIn(0, 100)
                watermarkBatch(uris, text, position, size, opacity)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun findSystemFont(): String? {
        val candidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/DroidSans.ttf"
        )
        return candidates.firstOrNull { File(it).exists() }
    }

    private fun watermarkBatch(uris: List<Uri>, text: String, position: Int, size: Int, opacity: Int, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部水印添加完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("添加水印")
        val inputFile = copyUriToCache(uris[index], "watermark_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val durationMs = getDurationMs(inputFile.absolutePath)
        val outputFile = File(cacheDir, "watermarked_${System.currentTimeMillis()}.mp4")

        val escaped = text.replace("\\", "\\\\").replace("'", "\\'").replace(":", "\\:")
        val xy = when (position) {
            0 -> "x=w-tw-20:y=h-th-20"
            1 -> "x=20:y=h-th-20"
            2 -> "x=w-tw-20:y=20"
            3 -> "x=20:y=20"
            else -> "x=(w-tw)/2:y=(h-th)/2"
        }
        val fontFile = findSystemFont()
        val opacityFloat = opacity / 100.0
        val drawtext = if (fontFile != null) {
            "drawtext=fontfile=$fontFile:text='$escaped':fontcolor=white@$opacityFloat:fontsize=$size:$xy"
        } else {
            "drawtext=text='$escaped':fontcolor=white@$opacityFloat:fontsize=$size:$xy"
        }

        statusText.text = "[${index + 1}/${uris.size}] 添加水印中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val command = arrayOf(
            "-y", "-i", inputFile.absolutePath, "-vf", drawtext,
            "-c:v", "h264_mediacodec", "-b:v", "4M",
            "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            outputFile.absolutePath
        )

        runFfmpeg(command, durationMs, progressLabel = "[${index + 1}/${uris.size}]水印中", noticeTitle = "添加水印", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 水印添加完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("添加水印", outputFile.name, "成功")
            inputFile.delete()
            watermarkBatch(uris, text, position, size, opacity, index + 1)
        }, onFailure = { msg ->
            statusText.text = "[${index + 1}/${uris.size}] 水印添加失败: ${FfmpegError.friendly(msg)} (可能设备不支持文字水印)"
            addHistory("添加水印", uris[index].toString(), "失败")
            inputFile.delete()
            watermarkBatch(uris, text, position, size, opacity, index + 1)
        })
    }

    // ================= 10. 字幕烧录(单个) =================

    private fun burnSubtitle(videoFile: File, srtFile: File) {
        val durationMs = getDurationMs(videoFile.absolutePath)
        val outputFile = File(cacheDir, "subtitled_${System.currentTimeMillis()}.mp4")

        statusText.text = "烧录字幕中..."
        progressBar.visibility = ProgressBar.VISIBLE
        startProcessingNotice("烧录字幕")

        val escapedSrtPath = srtFile.absolutePath.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
        val command = arrayOf(
            "-y", "-i", videoFile.absolutePath, "-vf", "subtitles=$escapedSrtPath",
            "-c:v", "h264_mediacodec", "-b:v", "4M",
            "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
            "-c:a", "copy",
            outputFile.absolutePath
        )

        runFfmpeg(command, durationMs, progressLabel = "字幕烧录中", noticeTitle = "烧录字幕", onSuccess = {
            statusText.text = "字幕烧录完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("烧录字幕", outputFile.name, "成功")
            videoFile.delete()
            srtFile.delete()
            pendingVideoForSubtitle = null
            stopProcessingNotice()
        }, onFailure = { msg ->
            statusText.text = "字幕烧录失败: ${FfmpegError.friendly(msg)} (提示: 该功能依赖设备ffmpeg是否支持字幕滤镜)"
            addHistory("烧录字幕", "失败", "失败")
            videoFile.delete()
            srtFile.delete()
            pendingVideoForSubtitle = null
            stopProcessingNotice()
        })
    }

    // ================= 11. 视频变速(批量) =================

    private fun showSpeedDialog(uris: List<Uri>) {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val presets = arrayOf("0.5x", "0.75x", "1.25x", "1.5x", "2x", "自定义")
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, presets)
        }
        val customInput = EditText(this).apply { hint = "自定义倍速，如 1.75" }
        container.addView(spinner)
        container.addView(customInput)

        AlertDialog.Builder(this)
            .setTitle("视频变速(共${uris.size}个文件)")
            .setView(container)
            .setPositiveButton("开始变速") { _, _ ->
                val speed = when (spinner.selectedItemPosition) {
                    0 -> 0.5; 1 -> 0.75; 2 -> 1.25; 3 -> 1.5; 4 -> 2.0
                    else -> customInput.text.toString().trim().toDoubleOrNull() ?: 1.0
                }
                if (speed <= 0) {
                    Toast.makeText(this, "倍速必须大于0", Toast.LENGTH_SHORT).show()
                } else {
                    speedBatch(uris, speed)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildAtempoFilter(speed: Double): String {
        var remaining = speed
        val factors = mutableListOf<Double>()
        while (remaining > 2.0) { factors.add(2.0); remaining /= 2.0 }
        while (remaining < 0.5) { factors.add(0.5); remaining /= 0.5 }
        factors.add(remaining)
        return factors.joinToString(",") { "atempo=%.4f".format(it) }
    }

    private fun speedBatch(uris: List<Uri>, speed: Double, index: Int = 0) {
        if (index >= uris.size) {
            statusText.text = "全部变速完成"
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return
        }
        if (index == 0) startProcessingNotice("视频变速")
        val inputFile = copyUriToCache(uris[index], "speed_input_${System.currentTimeMillis()}")
        if (!checkStorageOrWarn(inputFile.length())) { inputFile.delete(); return }
        val durationMs = getDurationMs(inputFile.absolutePath)
        val outputFile = File(cacheDir, "speed_${System.currentTimeMillis()}.mp4")
        val estOutMs = durationMs / speed

        statusText.text = "[${index + 1}/${uris.size}] 变速处理中..."
        progressBar.visibility = ProgressBar.VISIBLE

        val atempoChain = buildAtempoFilter(speed)
        val filterComplex = "[0:v]setpts=PTS/${speed}[v];[0:a]$atempoChain[a]"
        val command = arrayOf(
            "-y", "-i", inputFile.absolutePath,
            "-filter_complex", filterComplex,
            "-map", "[v]", "-map", "[a]",
            "-c:v", "h264_mediacodec", "-b:v", "4M",
            "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-ar", "44100",
            outputFile.absolutePath
        )

        runFfmpeg(command, estOutMs, progressLabel = "[${index + 1}/${uris.size}]变速中", noticeTitle = "视频变速", onSuccess = {
            statusText.text = "[${index + 1}/${uris.size}] 变速完成，正在保存..."
            saveToDownloads(outputFile, "video/*")
            addHistory("视频变速", outputFile.name, "成功(${speed}x)")
            inputFile.delete()
            speedBatch(uris, speed, index + 1)
        }, onFailure = {
            // 大概率是没有音轨导致的filter_complex失败，改为仅处理视频画面
            statusText.text = "[${index + 1}/${uris.size}] 含音轨变速失败，改为仅变速画面(静音)..."
            val command2 = arrayOf(
                "-y", "-i", inputFile.absolutePath,
                "-vf", "setpts=PTS/${speed}", "-an",
                "-c:v", "h264_mediacodec", "-b:v", "4M",
                "-profile:v", "baseline", "-level", "3.0", "-pix_fmt", "yuv420p",
                outputFile.absolutePath
            )
            runFfmpeg(command2, estOutMs, progressLabel = "[${index + 1}/${uris.size}]变速中", noticeTitle = "视频变速", onSuccess = {
                statusText.text = "[${index + 1}/${uris.size}] 变速完成(已静音)，正在保存..."
                saveToDownloads(outputFile, "video/*")
                addHistory("视频变速", outputFile.name, "成功(${speed}x,静音)")
                inputFile.delete()
                speedBatch(uris, speed, index + 1)
            }, onFailure = { msg ->
                statusText.text = "[${index + 1}/${uris.size}] 变速失败: ${FfmpegError.friendly(msg)}"
                addHistory("视频变速", uris[index].toString(), "失败")
                inputFile.delete()
                speedBatch(uris, speed, index + 1)
            })
        })
    }

    // ================= 12. 缩略图预览 =================

    private fun loadThumbnailsInto(path: String, container: LinearLayout) {
        Thread {
            val thumbs = ThumbnailUtil.extractThumbnails(path, 8)
            runOnUiThread {
                thumbs.forEach { t ->
                    val iv = ImageView(this).apply {
                        setImageBitmap(t.bitmap)
                        layoutParams = LinearLayout.LayoutParams(160, 90).apply { rightMargin = 6 }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    container.addView(iv)
                }
            }
        }.start()
    }

    private fun showThumbnailPreview(uri: Uri) {
        statusText.text = "正在提取缩略图..."
        val inputFile = copyUriToCache(uri, "thumb_${System.currentTimeMillis()}")

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 0) }
        val thumbRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply { addView(thumbRow) }
        container.addView(scroll)

        Thread {
            val thumbs = ThumbnailUtil.extractThumbnails(inputFile.absolutePath, 10)
            runOnUiThread {
                thumbs.forEach { t ->
                    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    val iv = ImageView(this).apply {
                        setImageBitmap(t.bitmap)
                        layoutParams = LinearLayout.LayoutParams(200, 112).apply { rightMargin = 8 }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    val label = TextView(this).apply {
                        text = formatDuration(t.timeSec)
                        textSize = 10f
                        gravity = android.view.Gravity.CENTER
                    }
                    column.addView(iv)
                    column.addView(label)
                    thumbRow.addView(column)
                }
                statusText.text = "选择一个操作开始"
            }
        }.start()

        AlertDialog.Builder(this)
            .setTitle("缩略图预览")
            .setView(container)
            .setPositiveButton("关闭") { _, _ -> inputFile.delete() }
            .show()
    }

    // ================= 13. 查看视频信息 =================

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

    // ================= 存储空间检查 / 缓存清理 =================

    private fun checkStorageOrWarn(estimatedInputBytes: Long): Boolean {
        if (!StorageUtil.hasEnoughSpaceFor(this, estimatedInputBytes)) {
            AlertDialog.Builder(this)
                .setTitle("存储空间不足")
                .setMessage("当前可用空间可能不够完成此任务。建议先点击「清理缓存」释放临时文件，或清理手机存储空间后重试。")
                .setPositiveButton("知道了", null)
                .show()
            progressBar.visibility = ProgressBar.INVISIBLE
            stopProcessingNotice()
            return false
        }
        return true
    }

    private fun showClearCacheDialog() {
        val size = StorageUtil.cacheSizeBytes(this)
        AlertDialog.Builder(this)
            .setTitle("清理缓存")
            .setMessage("当前临时缓存占用: ${StorageUtil.formatSize(size)}\n清理不会影响已保存到「下载」或「音乐」目录的文件。")
            .setPositiveButton("清理") { _, _ ->
                val freed = StorageUtil.clearCache(this)
                Toast.makeText(this, "已清理 ${StorageUtil.formatSize(freed)}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ================= 通知栏进度封装 =================

    private fun startProcessingNotice(title: String) {
        ProcessingService.start(this, title, "准备中...")
    }

    private fun updateProcessingNotice(title: String, percent: Int) {
        ProcessingService.update(this, title, "$percent%", percent)
    }

    private fun stopProcessingNotice() {
        ProcessingService.stop(this)
    }

    // ================= 暂停/继续(用于压缩、格式转换重编码等长任务) =================

    private data class PauseState(
        val taskLabel: String,
        val inputPath: String,
        val filterAndCodecArgs: List<String>,
        val outputExt: String,
        val totalDurationMs: Double,
        val segments: MutableList<File>,
        var elapsedMs: Double,
        val onFinalSuccess: (File) -> Unit,
        val onFinalFailure: (String) -> Unit
    )
    private var pauseState: PauseState? = null

    private fun runResumableEncode(
        taskLabel: String,
        inputPath: String,
        filterAndCodecArgs: List<String>,
        outputExt: String,
        totalDurationMs: Double,
        startOffsetMs: Double = 0.0,
        segments: MutableList<File> = mutableListOf(),
        onFinalSuccess: (File) -> Unit,
        onFinalFailure: (String) -> Unit
    ) {
        val segmentFile = File(cacheDir, "seg_${segments.size}_${System.currentTimeMillis()}.$outputExt")
        val cmd = mutableListOf("-y")
        if (startOffsetMs > 1.0) {
            cmd.add("-ss"); cmd.add((startOffsetMs / 1000.0).toString())
        }
        cmd.add("-i"); cmd.add(inputPath)
        cmd.addAll(filterAndCodecArgs)
        cmd.add(segmentFile.absolutePath)

        pauseState = PauseState(taskLabel, inputPath, filterAndCodecArgs, outputExt, totalDurationMs, segments, startOffsetMs, onFinalSuccess, onFinalFailure)
        showPauseButton()

        val remainingMs = (totalDurationMs - startOffsetMs).coerceAtLeast(1.0)
        runFfmpeg(
            cmd.toTypedArray(),
            remainingMs,
            progressLabel = taskLabel,
            noticeTitle = taskLabel,
            onProgressMs = { ms -> pauseState?.elapsedMs = startOffsetMs + ms },
            onSuccess = {
                segments.add(segmentFile)
                hidePauseResumeButtons()
                pauseState = null
                if (segments.size == 1) {
                    onFinalSuccess(segments[0])
                } else {
                    concatSegments(segments, outputExt, onFinalSuccess, onFinalFailure)
                }
            },
            onFailure = { msg ->
                hidePauseResumeButtons()
                pauseState = null
                segmentFile.delete()
                onFinalFailure(msg)
            }
        )
    }

    private fun concatSegments(segments: List<File>, outputExt: String, onFinalSuccess: (File) -> Unit, onFinalFailure: (String) -> Unit) {
        val listFile = File(cacheDir, "resume_concat_${System.currentTimeMillis()}.txt")
        listFile.bufferedWriter().use { w ->
            segments.forEach { f -> w.write("file '${f.absolutePath.replace("'", "'\\''")}'\n") }
        }
        val finalFile = File(cacheDir, "final_${System.currentTimeMillis()}.$outputExt")
        val cmd = arrayOf("-y", "-f", "concat", "-safe", "0", "-i", listFile.absolutePath, "-c", "copy", finalFile.absolutePath)
        runFfmpeg(cmd, onSuccess = {
            segments.forEach { it.delete() }
            listFile.delete()
            onFinalSuccess(finalFile)
        }, onFailure = { msg ->
            segments.forEach { it.delete() }
            listFile.delete()
            onFinalFailure(msg)
        })
    }

    private fun pauseCurrentTask() {
        val session = currentSession
        if (session == null || pauseState == null) {
            Toast.makeText(this, "当前没有可暂停的任务", Toast.LENGTH_SHORT).show()
            return
        }
        FFmpegKit.cancel(session.sessionId)
        statusText.text = "已暂停，可点击「继续」恢复"
        showResumeButton()
    }

    private fun resumeCurrentTask() {
        val st = pauseState
        if (st == null) {
            Toast.makeText(this, "没有可继续的任务", Toast.LENGTH_SHORT).show()
            return
        }
        statusText.text = "正在继续任务..."
        progressBar.visibility = ProgressBar.VISIBLE
        runResumableEncode(
            st.taskLabel, st.inputPath, st.filterAndCodecArgs, st.outputExt,
            st.totalDurationMs, st.elapsedMs, st.segments, st.onFinalSuccess, st.onFinalFailure
        )
    }

    private fun showPauseButton() {
        pauseButtonRef.visibility = Button.VISIBLE
        resumeButtonRef.visibility = Button.GONE
    }
    private fun showResumeButton() {
        pauseButtonRef.visibility = Button.GONE
        resumeButtonRef.visibility = Button.VISIBLE
    }
    private fun hidePauseResumeButtons() {
        pauseButtonRef.visibility = Button.GONE
        resumeButtonRef.visibility = Button.GONE
    }

    // ================= 通用ffmpeg执行封装(真实进度 + 通知栏同步 + 友好错误交由外层处理) =================

    private fun runFfmpeg(
        command: Array<String>,
        durationMs: Double = 0.0,
        progressLabel: String = "",
        noticeTitle: String = "视频处理",
        onProgressMs: ((Double) -> Unit)? = null,
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
                        if (pauseState == null) {
                            statusText.text = "任务已取消"
                            stopProcessingNotice()
                        }
                        // pauseState 不为空时说明是"暂停"触发的取消，UI已在pauseCurrentTask中处理
                    } else {
                        val logs = try { s.allLogsAsString } catch (e: Exception) { "" }
                        onFailure(logs.takeLast(300))
                    }
                }
            },
            LogCallback { },
            StatisticsCallback { stats ->
                onProgressMs?.invoke(stats.time.toDouble())
                if (durationMs > 0) {
                    val percent = ((stats.time / durationMs) * 100).toInt().coerceIn(0, 100)
                    runOnUiThread {
                        progressBar.progress = percent
                        if (progressLabel.isNotBlank()) statusText.text = "$progressLabel... $percent%"
                        updateProcessingNotice(noticeTitle, percent)
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
                NotificationHelper.notifyDone(this, "处理完成", file.name)
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
                NotificationHelper.notifyDone(this, "处理完成", file.name)
            } ?: run { statusText.text = "保存失败：无法创建目标文件" }
        } catch (e: Exception) {
            statusText.text = "保存失败: ${e.message}"
        }
    }
}
