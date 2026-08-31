package com.evidence.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMS = 100
        const val EXTRA_QUICK_ACTION = "quick_action"
        private const val COLOR_PRIMARY = 0xFF1B5E20.toInt()
        private const val COLOR_RED = 0xFFD32F2F.toInt()
        private const val COLOR_GREEN = 0xFF2E7D32.toInt()
        private const val COLOR_TEXT = 0xFF212121.toInt()
        private const val COLOR_TEXT_GRAY = 0xFF757575.toInt()
        private const val COLOR_BG = 0xFFF2F3F5.toInt()
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()
        private const val COLOR_DOT_IDLE = 0xFF9E9E9E.toInt()
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private val notesPrefs by lazy { getSharedPreferences("notes", MODE_PRIVATE) }

    private var pendingStart: String? = null

    private lateinit var audioDot: TextView
    private lateinit var audioTimer: TextView
    private lateinit var audioBtn: Button
    private lateinit var videoDot: TextView
    private lateinit var videoTimer: TextView
    private lateinit var videoBtn: Button
    private lateinit var statusLine: TextView
    private lateinit var batteryBtn: Button
    private lateinit var batteryHint: TextView

    private lateinit var pageRecord: ScrollView
    private lateinit var pageFiles: LinearLayout
    private lateinit var pageAuth: ScrollView
    private lateinit var tabRecord: TextView
    private lateinit var tabFiles: TextView
    private lateinit var tabAuth: TextView

    private lateinit var emptyHint: TextView
    private lateinit var fileListView: ListView
    private var fileAdapter: FileAdapter? = null
    private var files = listOf<FileItem>()
    private val thumbnailCache = HashMap<String, Bitmap>()


    private var lastAudioState = false
    private var lastVideoState = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                RecordService.ACTION_RECORDING_FINISHED -> {
                    refreshStatus()
                    refreshFiles()
                }
                RecordService.ACTION_STATE_CHANGED -> refreshStatus()
                RecordService.ACTION_ERROR -> Toast.makeText(
                    this@MainActivity,
                    intent.getStringExtra(RecordService.EXTRA_ERROR_MESSAGE) ?: "录制出错",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        refreshStatus()
        refreshFiles()
        handleQuickAction(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleQuickAction(intent)
    }

    /** 磁贴 / 长按快捷方式进入：直接开始（或停止）对应录制 */
    private fun handleQuickAction(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_QUICK_ACTION)) {
            "audio" -> { switchTab(0); toggleAudio() }
            "video" -> { switchTab(0); toggleVideo() }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(RecordService.ACTION_RECORDING_FINISHED)
            addAction(RecordService.ACTION_STATE_CHANGED)
            addAction(RecordService.ACTION_ERROR)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        uiHandler.postDelayed(uiTicker, 500)
        refreshStatus()
        refreshBatteryState()
    }

    override fun onStop() {
        unregisterReceiver(receiver)
        uiHandler.removeCallbacks(uiTicker)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshFiles()
        refreshBatteryState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) {
            val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                when (pendingStart) {
                    "audio" -> startAudioService()
                    "video" -> startVideoService()
                }
            }
            pendingStart = null
        }
    }

    // ---------------- 录制控制 ----------------

    private fun refreshStatus() {
        val a = RecordService.audioRecording
        val v = RecordService.videoRecording
        if ((lastAudioState && !a) || (lastVideoState && !v)) refreshFiles()
        lastAudioState = a
        lastVideoState = v

        audioDot.setTextColor(if (a) COLOR_RED else COLOR_DOT_IDLE)
        videoDot.setTextColor(if (v) COLOR_RED else COLOR_DOT_IDLE)
        audioTimer.text = "● " + (if (a) RecordService.formatElapsed(RecordService.audioElapsedMs) else "未录音")
        videoTimer.text = "● " + (if (v) RecordService.formatElapsed(RecordService.videoElapsedMs) else "未录像")
        audioTimer.setTextColor(if (a) COLOR_RED else COLOR_TEXT_GRAY)
        videoTimer.setTextColor(if (v) COLOR_RED else COLOR_TEXT_GRAY)

        audioBtn.text = if (a) "停止录音" else "开始录音"
        audioBtn.background = buttonBg(if (a) COLOR_RED else COLOR_GREEN)
        videoBtn.text = if (v) "停止录像" else "开始录像"
        videoBtn.background = buttonBg(if (v) COLOR_RED else COLOR_GREEN)

        statusLine.text = when {
            a && v -> "录音 + 录像进行中"
            a -> "录音进行中"
            v -> "录像进行中"
            else -> "未在录制"
        }
    }

    private fun ensurePermissions(): Boolean {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.RECORD_AUDIO
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            needed += Manifest.permission.CAMERA
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) needed += Manifest.permission.POST_NOTIFICATIONS
        if (needed.isNotEmpty()) {
            requestPermissions(needed.toTypedArray(), REQ_PERMS)
            return false
        }
        return true
    }

    private fun toggleAudio() {
        if (RecordService.audioRecording) {
            ContextCompat.startForegroundService(this, Intent(this, RecordService::class.java)
                .setAction(RecordService.ACTION_STOP_AUDIO))
        } else {
            if (!ensurePermissions()) { pendingStart = "audio"; return }
            startAudioService()
        }
    }

    private fun toggleVideo() {
        if (RecordService.videoRecording) {
            ContextCompat.startForegroundService(this, Intent(this, RecordService::class.java)
                .setAction(RecordService.ACTION_STOP_VIDEO))
        } else {
            if (!ensurePermissions()) { pendingStart = "video"; return }
            startVideoService()
        }
    }

    private fun startAudioService() {
        ContextCompat.startForegroundService(this, Intent(this, RecordService::class.java)
            .setAction(RecordService.ACTION_START_AUDIO))
        refreshStatus()
    }

    private fun startVideoService() {
        ContextCompat.startForegroundService(this, Intent(this, RecordService::class.java)
            .setAction(RecordService.ACTION_START_VIDEO))
        refreshStatus()
    }

    private fun buildBatteryCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            elevation = d(2).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(d(12), d(6), d(12), d(6)) }
        card.layoutParams = lp
        card.setPadding(d(16), d(14), d(16), d(14))

        card.addView(TextView(this).apply {
            text = "锁屏持续录制（重要）"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
        })
        batteryHint = TextView(this).apply {
            text = "锁屏持续录制需要系统授权后台运行"
            textSize = 12f
            setTextColor(COLOR_TEXT_GRAY)
            setPadding(0, d(4), 0, 0)
        }
        card.addView(batteryHint)
        batteryBtn = Button(this).apply {
            text = "申请忽略电池优化"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = buttonBg(COLOR_PRIMARY)
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                ))
            }
        }
        card.addView(batteryBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(44)
        ).apply { topMargin = d(10) })
        return card
    }

    // ---------------- 界面构建 ----------------

    private fun d(v: Number): Int = (v.toFloat() * resources.displayMetrics.density).toInt()

    private fun cardBg(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = d(18).toFloat()
            setColor(COLOR_CARD)
            setStroke(d(1), 0xFFE0E0E0.toInt())
        }

    private fun buttonBg(color: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = d(24).toFloat()
            setColor(color)
        }

    @Suppress("DiscouragedApi")
    private fun systemBarSize(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    private fun buildUi() {
        val statusBarH = systemBarSize("status_bar_height")
        val navBarH = systemBarSize("navigation_bar_height")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
            setPadding(0, statusBarH, 0, 0)
        }

        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---------- Tab 1：录制页（整页可滚动） ----------
        pageRecord = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
            setPadding(0, 0, 0, navBarH)
            clipToPadding = false
        }
        val recordCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageRecord.addView(recordCol)
        content.addView(pageRecord)

        // 标题区
        val titleWrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(20), d(20), d(20), d(16))
        }
        titleWrap.addView(TextView(this).apply {
            text = "取证记录"
            textSize = 24f
            setTextColor(COLOR_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
        })
        statusLine = TextView(this).apply {
            textSize = 13f
            setTextColor(COLOR_TEXT_GRAY)
            setPadding(0, d(4), 0, 0)
        }
        titleWrap.addView(statusLine)
        recordCol.addView(titleWrap)

        recordCol.addView(buildRecordCard(true))
        recordCol.addView(buildRecordCard(false))
        recordCol.addView(buildSettingsCard())

        // ---------- Tab 2：文件页 ----------
        pageFiles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        content.addView(pageFiles)
        pageFiles.addView(TextView(this).apply {
            text = "录制文件"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(d(20), d(18), d(20), d(8))
        })
        emptyHint = TextView(this).apply {
            text = "暂无录制文件"
            textSize = 13f
            setTextColor(COLOR_TEXT_GRAY)
            gravity = Gravity.CENTER
            setPadding(0, d(20), 0, d(20))
            visibility = View.GONE
        }
        pageFiles.addView(emptyHint)
        fileListView = ListView(this).apply {
            divider = null
            setPadding(0, 0, 0, navBarH + d(8))
            clipToPadding = false
        }
        fileAdapter = FileAdapter().also { fileListView.adapter = it }
        pageFiles.addView(fileListView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // ---------- Tab 2：授权管理页 ----------
        pageAuth = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
            setPadding(0, 0, 0, navBarH)
            clipToPadding = false
            visibility = View.GONE
        }
        val authCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageAuth.addView(authCol)
        content.addView(pageAuth)
        authCol.addView(TextView(this).apply {
            text = "授权管理"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(d(20), d(18), d(20), d(8))
        })
        authCol.addView(buildBatteryCard())
        authCol.addView(TextView(this).apply {
            text = "提示：锁屏持续录制需要系统授权后台运行。除电池优化外，\n" +
                    "建议在系统设置 → 应用 → 取证记录 中开启「允许后台运行」与「自启动」。"
            textSize = 12f
            setTextColor(COLOR_TEXT_GRAY)
            setPadding(d(20), d(8), d(20), d(16))
            setLineSpacing(d(3).toFloat(), 1f)
        })

        // ---------- 底部 Tab 栏（白色延伸到手势条区域） ----------
        val tabBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        tabBar.addView(View(this).apply {
            setBackgroundColor(0xFFE0E0E0.toInt())
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, d(1)))

        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, navBarH)
        }
        fun makeTab(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 14f
            gravity = Gravity.CENTER
        }
        tabRecord = makeTab("录制")
        tabFiles = makeTab("文件")
        tabAuth = makeTab("授权")
        tabRecord.setOnClickListener { switchTab(0) }
        tabFiles.setOnClickListener { switchTab(1) }
        tabAuth.setOnClickListener { switchTab(2) }
        tabRow.addView(tabRecord, LinearLayout.LayoutParams(0, d(52), 1f))
        tabRow.addView(tabFiles, LinearLayout.LayoutParams(0, d(52), 1f))
        tabRow.addView(tabAuth, LinearLayout.LayoutParams(0, d(52), 1f))
        tabBar.addView(tabRow)
        root.addView(tabBar)

        setContentView(root)
        switchTab(0)
    }

    private fun switchTab(index: Int) {
        pageRecord.visibility = if (index == 0) View.VISIBLE else View.GONE
        pageFiles.visibility = if (index == 1) View.VISIBLE else View.GONE
        pageAuth.visibility = if (index == 2) View.VISIBLE else View.GONE
        val tabs = arrayOf(tabRecord, tabFiles, tabAuth)
        for (i in tabs.indices) {
            tabs[i].setTextColor(if (i == index) COLOR_PRIMARY else COLOR_TEXT_GRAY)
            tabs[i].typeface = if (i == index) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        when (index) {
            1 -> refreshFiles()
            2 -> refreshBatteryState()
        }
    }

    private fun buildRecordCard(isAudio: Boolean): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            elevation = d(2).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(d(12), d(6), d(12), d(6)) }
        card.layoutParams = lp
        card.setPadding(d(16), d(14), d(16), d(14))

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val dot = TextView(this).apply { text = "●"; textSize = 14f; setTextColor(COLOR_DOT_IDLE) }
        topRow.addView(dot)
        topRow.addView(TextView(this).apply {
            text = if (isAudio) "录音" else "录像"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
            setPadding(d(8), 0, 0, 0)
        })
        val timer = TextView(this).apply {
            text = "● 未${if (isAudio) "录音" else "录像"}"
            textSize = 14f
            setTextColor(COLOR_TEXT_GRAY)
            gravity = Gravity.END
            typeface = Typeface.MONOSPACE
        }
        topRow.addView(timer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(topRow)

        val btn = Button(this).apply {
            text = if (isAudio) "开始录音" else "开始录像"
            textSize = 15f
            setTextColor(Color.WHITE)
            background = buttonBg(COLOR_GREEN)
            isAllCaps = false
            setOnClickListener {
                if (isAudio) toggleAudio() else toggleVideo()
            }
        }
        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(50)
        ).apply { topMargin = d(12) }
        card.addView(btn, btnLp)

        if (isAudio) { audioDot = dot; audioTimer = timer; audioBtn = btn }
        else { videoDot = dot; videoTimer = timer; videoBtn = btn }
        return card
    }

    private fun buildSettingsCard(): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg()
            elevation = d(2).toFloat()
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(d(12), d(6), d(12), d(6)) }
        card.layoutParams = lp
        card.setPadding(d(16), d(12), d(16), d(12))

        card.addView(TextView(this).apply {
            text = "录制设置"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT)
        })
        card.addView(TextView(this).apply {
            text = "拖动滑块调参数，点 ? 看解释"
            textSize = 12f
            setTextColor(COLOR_TEXT_GRAY)
            setPadding(0, d(2), 0, d(4))
        })

        // 录音时长：10~480 分钟，步进 10
        addSliderRow(
            card, "录音时长",
            "录到设定时间自动停止，不用守着。录得越久文件越大。",
            47,
            (((prefs.getLong("audio_duration_ms", 7_200_000L) / 60000L - 10) / 10).coerceIn(0, 47)).toInt(),
            valueText = { p -> formatMinutes(10 + p * 10) },
            onProgress = { p -> prefs.edit().putLong("audio_duration_ms", (10L + p * 10L) * 60000L).apply() }
        )

        // 录音编码：AAC / AMR / OPUS 三档
        addSliderRow(
            card, "录音编码",
            "AAC：通用最稳，默认选它。\nAMR：体积最小（省约80%），但音质一般，适合超长录音。\nOPUS：音质最好，体积中等。",
            2,
            prefs.getInt("audio_encoder", 0).coerceIn(0, 2),
            valueText = { p -> ENCODER_LABELS[p] },
            onProgress = { p -> prefs.edit().putInt("audio_encoder", p).apply() }
        )

        // 录像时长：5~120 分钟，步进 5
        addSliderRow(
            card, "录像时长",
            "录到设定时间自动停止，不用守着。录得越久文件越大。",
            23,
            (((prefs.getLong("video_duration_ms", 1_800_000L) / 60000L - 5) / 5).coerceIn(0, 23)).toInt(),
            valueText = { p -> formatMinutes(5 + p * 5) },
            onProgress = { p -> prefs.edit().putLong("video_duration_ms", (5L + p * 5L) * 60000L).apply() }
        )

        // 录像分辨率：480P / 720P / 1080P / 4K
        addSliderRow(
            card, "录像分辨率",
            "越高越清晰、文件越大。480P 适合超长监控，1080P 日常取证够用，4K 需要手机支持（不支持会自动回退）。",
            3,
            prefs.getInt("video_quality", 1).coerceIn(0, 3),
            valueText = { p -> QUALITY_LABELS[p] },
            onProgress = { p -> prefs.edit().putInt("video_quality", p).apply() }
        )

        // 录像码率：0=自动，1~20 Mbps
        addSliderRow(
            card, "录像码率",
            "码率越高越清晰、文件越大。最左端「自动」最省心，系统按分辨率自动配。",
            20,
            (prefs.getInt("video_bitrate", 0) / 1_000_000).coerceIn(0, 20),
            valueText = { p -> if (p == 0) "自动" else "$p Mbps" },
            onProgress = { p -> prefs.edit().putInt("video_bitrate", p * 1_000_000).apply() }
        )

        // 录像帧率：自动 / 24 / 30 / 60
        addSliderRow(
            card, "录像帧率",
            "帧率越高越流畅、文件越大。日常 30 帧够用，想要慢动作感可以选 60。",
            3,
            intArrayOf(0, 24, 30, 60).indexOf(prefs.getInt("video_fps", 0)).let { if (it < 0) 0 else it },
            valueText = { p -> FPS_LABELS[p] },
            onProgress = { p -> prefs.edit().putInt("video_fps", intArrayOf(0, 24, 30, 60)[p]).apply() }
        )

        return card
    }

    private val ENCODER_LABELS = arrayOf("AAC", "AMR", "OPUS")
    private val QUALITY_LABELS = arrayOf("480P", "720P", "1080P", "4K")
    private val FPS_LABELS = arrayOf("自动", "24 fps", "30 fps", "60 fps")

    private fun formatMinutes(min: Int): String = when {
        min < 60 -> "${min}分钟"
        min % 60 == 0 -> "${min / 60}小时"
        else -> "${min / 60}小时${min % 60}分"
    }

    /** 一行滑块：分组 cell 容器 + 标题/值徽章/? 帮助 + 定制绿色滑块 */
    private fun addSliderRow(
        parent: LinearLayout,
        label: String,
        help: String,
        max: Int,
        progress: Int,
        valueText: (Int) -> String,
        onProgress: (Int) -> Unit
    ) {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = d(14).toFloat()
                setColor(0xFFF6F7F9.toInt())
            }
        }
        parent.addView(cell, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = d(5); bottomMargin = d(5) })
        cell.setPadding(d(14), d(10), d(14), d(12))

        val headRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cell.addView(headRow)
        headRow.addView(TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(0xFF3A3F44.toInt())
            typeface = Typeface.DEFAULT_BOLD
        })
        val value = TextView(this).apply {
            textSize = 12f
            setTextColor(COLOR_PRIMARY)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = d(11).toFloat()
                setColor(0xFFE4F2E6.toInt())
            }
            setPadding(d(12), d(4), d(12), d(4))
        }
        headRow.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            gravity = Gravity.END
        })
        val helpBtn = TextView(this).apply {
            text = "?"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF9FB3A6.toInt())
            }
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(label)
                    .setMessage(help)
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
        headRow.addView(helpBtn, LinearLayout.LayoutParams(d(20), d(20)).apply { setMargins(d(10), 0, 0, 0) })

        val seek = SeekBar(this).apply {
            this.max = max
            setProgress(progress.coerceIn(0, max))
            setPadding(0, 0, 0, 0)
            splitTrack = false
            setThumb(sliderThumb())
            progressDrawable = sliderTrack()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    value.text = valueText(p)
                    onProgress(p)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        value.text = valueText(seek.progress)
        cell.addView(seek, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, d(36)
        ).apply { topMargin = d(2) })
    }

    /** 滑块轨道：圆角灰色底 + 主色进度（ClipDrawable 控制宽度） */
    private fun sliderTrack(): Drawable {
        val track = GradientDrawable().apply {
            cornerRadius = d(5).toFloat()
            setColor(0xFFE3E6EA.toInt())
        }
        val progressShape = GradientDrawable().apply {
            cornerRadius = d(5).toFloat()
            setColor(COLOR_PRIMARY)
        }
        val clip = ClipDrawable(progressShape, Gravity.START, ClipDrawable.HORIZONTAL)
        val layer = LayerDrawable(arrayOf(track, track, clip))
        layer.setId(0, android.R.id.background)
        layer.setId(1, android.R.id.secondaryProgress)
        layer.setId(2, android.R.id.progress)
        // 上下内缩让轨道变细（视觉厚度约 8dp）
        return InsetDrawable(layer, 0, d(14), 0, d(14))
    }

    /** 滑块拇指：主色实心圆 + 白边 */
    private fun sliderThumb(): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(COLOR_PRIMARY)
            setStroke(d(3), Color.WHITE)
            setSize(d(22), d(22))
        }

    private fun refreshBatteryState() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val ignored = pm.isIgnoringBatteryOptimizations(packageName)
        if (ignored) {
            batteryBtn.text = "已忽略（电池优化白名单）"
            batteryBtn.background = buttonBg(COLOR_GREEN)
            batteryHint.text = "已授权后台运行，锁屏录制更稳定"
        } else {
            batteryBtn.text = "申请忽略电池优化"
            batteryBtn.background = buttonBg(COLOR_PRIMARY)
            batteryHint.text = "锁屏持续录制需要系统授权后台运行"
        }
    }

    // ---------------- 文件管理 ----------------

    private data class FileItem(
        val file: File,
        val isVideo: Boolean,
        val durationMs: Long,
        val size: Long,
        val note: String
    )

    private fun refreshFiles() {
        val list = mutableListOf<FileItem>()
        for (dirName in arrayOf("录音", "录像")) {
            val dir = File(getExternalFilesDir(null), dirName)
            val arr = dir.listFiles() ?: continue
            for (f in arr) {
                if (!f.isFile) continue
                val isVideo = f.extension.equals("mp4", ignoreCase = true)
                if (!isVideo &&
                    !f.extension.equals("m4a", ignoreCase = true) &&
                    !f.extension.equals("amr", ignoreCase = true) &&
                    !f.extension.equals("ogg", ignoreCase = true)
                ) continue
                val duration = mediaDuration(f)
                list.add(FileItem(f, isVideo, duration, f.length(),
                    notesPrefs.getString(f.absolutePath, "") ?: ""))
            }
        }
        list.sortByDescending { it.file.lastModified() }
        files = list
        emptyHint.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        fileAdapter?.notifyDataSetChanged()
    }

    private fun mediaDuration(file: File): Long = try {
        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(file.absolutePath)
        val d = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        mmr.release()
        d
    } catch (e: Exception) {
        0L
    }

    private fun getThumbnail(file: File): Bitmap? {
        if (!file.extension.equals("mp4", ignoreCase = true)) return null
        val path = file.absolutePath
        thumbnailCache[path]?.let { return it }
        return try {
            val mmr = MediaMetadataRetriever()
            mmr.setDataSource(path)
            val bmp = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            mmr.release()
            if (bmp != null) thumbnailCache[path] = bmp
            bmp
        } catch (e: Exception) {
            null
        }
    }

    private fun mimeOf(item: FileItem): String = when (item.file.extension.lowercase()) {
        "mp4" -> "video/mp4"
        "m4a" -> "audio/mp4"
        "amr" -> "audio/amr"
        "ogg" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    private fun viewFile(item: FileItem) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", item.file)
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, mimeOf(item))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开文件", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportFile(item: FileItem) {
        val isVideo = item.isVideo
        val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val relPath = if (isVideo) "${Environment.DIRECTORY_MOVIES}/取证录像"
        else "${Environment.DIRECTORY_MUSIC}/取证录音"
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(item))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(collection, values)
                ?: throw Exception("无法创建导出项")
            contentResolver.openOutputStream(uri)?.use { os ->
                item.file.inputStream().use { it.copyTo(os) }
            } ?: throw Exception("无法写入文件")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            Toast.makeText(this, "已导出到${if (isVideo) "相册" else "音乐库"}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteFile(item: FileItem) {
        AlertDialog.Builder(this)
            .setTitle("删除文件")
            .setMessage("确定删除「${item.file.name}」吗？删除后不可恢复。")
            .setPositiveButton("删除") { _, _ ->
                if (item.file.delete()) {
                    thumbnailCache.remove(item.file.absolutePath)
                    notesPrefs.edit().remove(item.file.absolutePath).apply()
                    refreshFiles()
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun editNote(item: FileItem) {
        val pad = d(20)
        val input = EditText(this).apply {
            setText(item.note)
            hint = "输入备注"
            setPadding(pad, d(8), pad, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("备注")
            .setView(input)
            .setPositiveButton("保存") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) notesPrefs.edit().remove(item.file.absolutePath).apply()
                else notesPrefs.edit().putString(item.file.absolutePath, text).apply()
                refreshFiles()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        if (totalSec <= 0) return "--:--"
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    // ---------------- 列表适配器 ----------------

    private inner class FileAdapter : BaseAdapter() {
        override fun getCount(): Int = files.size
        override fun getItem(position: Int): Any = files[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = files[position]
            val holder: ViewHolder
            var v = convertView
            if (v == null) {
                v = buildRow()
                holder = v.tag as ViewHolder
            } else {
                holder = v.tag as ViewHolder
            }
            holder.bind(item)
            return v
        }

        private fun buildRow(): View {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg()
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(d(12), d(4), d(12), d(4)) }
            row.layoutParams = lp
            row.setPadding(d(12), d(10), d(12), d(10))

            // 第一行：缩略图 + 文件信息
            val line1 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val thumb = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
            line1.addView(thumb, LinearLayout.LayoutParams(d(72), d(72)))

            val mid = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val name = TextView(this@MainActivity).apply {
                textSize = 14f
                setTextColor(COLOR_TEXT)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val meta = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(COLOR_TEXT_GRAY)
            }
            val note = TextView(this@MainActivity).apply {
                textSize = 12f
                setTextColor(COLOR_PRIMARY)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            mid.addView(name)
            mid.addView(meta, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(2) })
            mid.addView(note, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(2) })
            line1.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(d(10), 0, 0, 0)
            })
            row.addView(line1)

            // 第二行：功能按钮单列一行
            val line2 = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            fun smallBtn(text: String, textColor: Int): Button = Button(this@MainActivity).apply {
                this.text = text
                textSize = 12f
                isAllCaps = false
                setTextColor(textColor)
                background = GradientDrawable().apply {
                    cornerRadius = d(8).toFloat()
                    setColor(0xFFFFFFFF.toInt())
                    setStroke(d(1), if (textColor == COLOR_RED) COLOR_RED else COLOR_PRIMARY)
                }
            }
            val btnExport = smallBtn("导出", COLOR_PRIMARY)
            val btnNote = smallBtn("备注", COLOR_PRIMARY)
            val btnDelete = smallBtn("删除", COLOR_RED)
            for (b in listOf(btnExport, btnNote, btnDelete)) {
                line2.addView(b, LinearLayout.LayoutParams(0, d(32), 1f).apply {
                    setMargins(0, 0, d(6), 0)
                })
            }
            row.addView(line2, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = d(10) })

            val holder = ViewHolder(thumb, name, meta, note, btnExport, btnNote, btnDelete)
            row.tag = holder
            return row
        }

        private inner class ViewHolder(
            val thumb: ImageView,
            val name: TextView,
            val meta: TextView,
            val note: TextView,
            val btnExport: Button,
            val btnNote: Button,
            val btnDelete: Button
        ) {
            fun bind(item: FileItem) {
                thumb.setImageResource(if (item.isVideo) 0 else android.R.drawable.ic_btn_speak_now)
                thumb.setBackgroundColor(if (item.isVideo) 0xFF000000.toInt() else Color.TRANSPARENT)
                val bmp = if (item.isVideo) getThumbnail(item.file) else null
                if (bmp != null) thumb.setImageBitmap(bmp)
                else if (!item.isVideo) thumb.setImageResource(android.R.drawable.ic_btn_speak_now)

                name.text = item.file.name
                meta.text = "${formatDuration(item.durationMs)}  ·  ${formatSize(item.size)}"
                note.text = if (item.note.isNotEmpty()) "备注：${item.note}" else ""
                // 点击缩略图/文件名直接预览，省掉"查看"按钮
                thumb.setOnClickListener { viewFile(item) }
                name.setOnClickListener { viewFile(item) }
                btnExport.setOnClickListener { exportFile(item) }
                btnNote.setOnClickListener { editNote(item) }
                btnDelete.setOnClickListener { deleteFile(item) }
            }
        }
    }
}
