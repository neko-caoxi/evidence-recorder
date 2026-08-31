package com.evidence.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Range
import androidx.camera.core.CameraSelector
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordService : Service() {

    companion object {
        const val ACTION_START_AUDIO = "com.evidence.recorder.START_AUDIO"
        const val ACTION_START_VIDEO = "com.evidence.recorder.START_VIDEO"
        const val ACTION_STOP_AUDIO = "com.evidence.recorder.STOP_AUDIO"
        const val ACTION_STOP_VIDEO = "com.evidence.recorder.STOP_VIDEO"
        const val ACTION_STOP = "com.evidence.recorder.STOP"
        const val ACTION_RECORDING_FINISHED = "com.evidence.recorder.FINISHED"
        const val ACTION_STATE_CHANGED = "com.evidence.recorder.STATE_CHANGED"
        const val ACTION_ERROR = "com.evidence.recorder.ERROR"
        const val EXTRA_ERROR_MESSAGE = "message"

        private const val NOTIF_ID_AUDIO = 1001
        private const val NOTIF_ID_VIDEO = 1002
        private const val CHANNEL_ID = "recording_status"
        private const val AUDIO_DIR = "录音"
        private const val VIDEO_DIR = "录像"

        @Volatile
        var audioRecording = false
        @Volatile
        var videoRecording = false
        @Volatile
        var audioElapsedMs = 0L
        @Volatile
        var videoElapsedMs = 0L

        fun formatElapsed(ms: Long): String {
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    private var audioStopRunnable: Runnable? = null

    private var recording: Recording? = null
    private var videoFile: File? = null
    private var videoStopRunnable: Runnable? = null
    private var cameraProvider: ProcessCameraProvider? = null

    // Service 没有 LifecycleOwner，自定义一个状态恒为 RESUMED 的。
    // 当前传递依赖解析为 lifecycle 2.3.x（Java 接口），必须写 override fun getLifecycle()
    private val lifecycleOwner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply {
            markState(Lifecycle.State.RESUMED)
        }
        override fun getLifecycle(): Lifecycle = registry
    }

    private val ticker = object : Runnable {
        override fun run() {
            val a = audioRecording
            val v = videoRecording
            if (a) {
                audioElapsedMs += 1000
                nm.notify(NOTIF_ID_AUDIO, buildAudioNotification())
            }
            if (v) {
                videoElapsedMs += 1000
                nm.notify(NOTIF_ID_VIDEO, buildVideoNotification())
            }
            if (a || v) handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
        // 锁屏防 CPU 休眠（onCreate 获取，全部停止后释放）
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RecordService:lock").apply { acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUDIO -> startAudio()
            ACTION_START_VIDEO -> startVideo()
            ACTION_STOP_AUDIO -> stopAudio()
            ACTION_STOP_VIDEO -> stopVideo()
            ACTION_STOP -> { stopAudio(); stopVideo() }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ---------------- 录音 ----------------

    private data class AudioSpec(
        val outputFormat: Int,
        val audioEncoder: Int,
        val samplingRate: Int,
        val bitRate: Int,
        val ext: String
    )

    private fun startAudio() {
        if (audioRecording) return
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val durationMs = prefs.getLong("audio_duration_ms", 7_200_000L)
        val encoder = prefs.getInt("audio_encoder", 0)
        // 编码 → 容器/采样率/码率/扩展名（AMR 码率固定，AAC 默认 192k，OPUS 96k 高清）
        val spec = when (encoder) {
            1 -> AudioSpec(MediaRecorder.OutputFormat.AMR_NB, MediaRecorder.AudioEncoder.AMR_NB, 8000, 12200, "amr")
            2 -> AudioSpec(MediaRecorder.OutputFormat.OGG, MediaRecorder.AudioEncoder.OPUS, 48000, 96000, "ogg")
            else -> AudioSpec(MediaRecorder.OutputFormat.MPEG_4, MediaRecorder.AudioEncoder.AAC, 44100, 192000, "m4a")
        }
        val file = createOutputFile(AUDIO_DIR, "录音", spec.ext) ?: return

        // 先置位状态标志，再 startForeground（type 必须合法，否则 Android 14+ 闪退），最后启动录制
        audioRecording = true
        audioElapsedMs = 0
        audioFile = file
        updateNotifications()
        startTicker()

        val stopRunnable = Runnable { stopAudio() }
        audioStopRunnable = stopRunnable
        handler.postDelayed(stopRunnable, durationMs)

        try {
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(spec.outputFormat)
            r.setAudioEncoder(spec.audioEncoder)
            r.setAudioSamplingRate(spec.samplingRate)
            r.setAudioEncodingBitRate(spec.bitRate)
            r.setOutputFile(file.absolutePath)
            r.setOnErrorListener { _, _, _ -> stopAudio() }
            r.prepare()
            r.start()
            mediaRecorder = r
            broadcastStateChanged()
        } catch (e: Exception) {
            audioRecording = false
            handler.removeCallbacks(stopRunnable)
            audioStopRunnable = null
            audioFile = null
            runCatching { file.delete() }
            broadcastError("录音启动失败：${e.message}")
            maybeStopService()
        }
    }

    private fun stopAudio() {
        if (!audioRecording) return
        audioRecording = false
        audioStopRunnable?.let { handler.removeCallbacks(it) }
        audioStopRunnable = null
        try {
            // 录制 <1 秒 stop() 会抛异常，需丢弃文件
            mediaRecorder?.stop()
        } catch (e: Exception) {
            audioFile?.let { runCatching { it.delete() } }
        }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        audioFile = null
        nm.cancel(NOTIF_ID_AUDIO)
        broadcastStateChanged()
        broadcastFinished()
        maybeStopService()
    }

    // ---------------- 录像 ----------------

    private fun startVideo() {
        if (videoRecording) return
        // 防御性权限检查（UI 侧已请求，服务侧兜底）
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            broadcastError("缺少录音或相机权限，无法录像")
            return
        }
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val durationMs = prefs.getLong("video_duration_ms", 1_800_000L)
        val bitrate = prefs.getInt("video_bitrate", 0)
        val fps = prefs.getInt("video_fps", 0)
        val quality = when (prefs.getInt("video_quality", 1)) {
            0 -> Quality.SD
            2 -> Quality.FHD
            3 -> Quality.UHD
            else -> Quality.HD
        }
        val file = createOutputFile(VIDEO_DIR, "录像", "mp4") ?: return

        videoRecording = true
        videoElapsedMs = 0
        videoFile = file
        updateNotifications()
        startTicker()

        val stopRunnable = Runnable { stopVideo() }
        videoStopRunnable = stopRunnable
        handler.postDelayed(stopRunnable, durationMs)

        val mainExecutor = ContextCompat.getMainExecutor(this)
        ProcessCameraProvider.getInstance(this).addListener({
            try {
                val provider = ProcessCameraProvider.getInstance(this).get()
                val recorderBuilder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            quality,
                            FallbackStrategy.higherQualityOrLowerThan(quality)
                        )
                    )
                if (bitrate > 0) recorderBuilder.setTargetVideoEncodingBitRate(bitrate)
                val recorder = recorderBuilder.build()
                // 帧率在 VideoCapture 上设置（Recorder.Builder 没有帧率方法）
                val videoCapture = if (fps > 0) {
                    VideoCapture.Builder(recorder).setTargetFrameRate(Range(fps, fps)).build()
                } else {
                    VideoCapture.withOutput(recorder)
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)
                cameraProvider = provider

                val outputOptions = FileOutputOptions.Builder(file).build()
                recording = recorder.prepareRecording(this, outputOptions)
                    .withAudioEnabled()
                    .start(mainExecutor) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            if (event.error != VideoRecordEvent.Finalize.ERROR_NONE &&
                                event.error != VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE
                            ) {
                                // 出错清理（主动停止触发的 SOURCE_INACTIVE 属正常，不删）
                                runCatching { file.delete() }
                            }
                            videoFile = null
                            maybeStopService()
                        }
                    }
                broadcastStateChanged()
            } catch (e: Exception) {
                videoRecording = false
                handler.removeCallbacks(stopRunnable)
                videoStopRunnable = null
                videoFile = null
                runCatching { file.delete() }
                broadcastError("录像启动失败：${e.message}")
                maybeStopService()
            }
        }, mainExecutor)
    }

    private fun stopVideo() {
        if (!videoRecording) return
        videoRecording = false
        videoStopRunnable?.let { handler.removeCallbacks(it) }
        videoStopRunnable = null
        runCatching { recording?.stop() } // 异步 Finalize，完成后回调清理
        recording = null
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        nm.cancel(NOTIF_ID_VIDEO)
        broadcastStateChanged()
        broadcastFinished()
        maybeStopService()
    }

    // ---------------- 前台通知 / 生命周期 ----------------

    /** 根据当前活跃通道切换前台通知与 FGS 类型（锁屏收音必须同时含 CAMERA|MICROPHONE） */
    private fun updateNotifications() {
        if (!audioRecording && !videoRecording) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        val type = (if (audioRecording) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0) or
                (if (videoRecording) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val primaryId = if (videoRecording) NOTIF_ID_VIDEO else NOTIF_ID_AUDIO
        val primary = if (videoRecording) buildVideoNotification() else buildAudioNotification()
        startForeground(primaryId, primary, type)
        // 另一通道作为普通通知维持显示
        if (audioRecording) nm.notify(NOTIF_ID_AUDIO, buildAudioNotification())
        if (videoRecording) nm.notify(NOTIF_ID_VIDEO, buildVideoNotification())
    }

    private fun maybeStopService() {
        if (!audioRecording && !videoRecording) {
            handler.removeCallbacksAndMessages(null)
            wakeLock?.let { if (it.isHeld) it.release() }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotifications()
        }
    }

    private fun startTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1000)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "录制状态", NotificationManager.IMPORTANCE_LOW)
        channel.description = "录制进行中的实时状态"
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildAudioNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("取证记录")
            .setContentText("● 录音中 ${formatElapsed(audioElapsedMs)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(0)
            .build()

    private fun buildVideoNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("取证记录")
            .setContentText("● 录像中 ${formatElapsed(videoElapsedMs)}")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setWhen(0)
            .build()

    private fun createOutputFile(dirName: String, prefix: String, ext: String): File? {
        val dir = File(getExternalFilesDir(null), dirName)
        if (!dir.exists() && !dir.mkdirs()) return null
        val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // 命名：类型+日期+4位防重名编码（去掉了 0/O/1/I 等易混字符）
        val code = (1..4).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
        return File(dir, "${prefix}_${time}_$code.$ext")
    }

    private fun broadcastFinished() {
        sendBroadcast(Intent(ACTION_RECORDING_FINISHED).setPackage(packageName))
    }

    private fun broadcastStateChanged() {
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
    }

    private fun broadcastError(message: String) {
        sendBroadcast(Intent(ACTION_ERROR).setPackage(packageName).putExtra(EXTRA_ERROR_MESSAGE, message))
    }
}
