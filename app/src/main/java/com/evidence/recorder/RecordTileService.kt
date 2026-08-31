package com.evidence.recorder

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.core.content.ContextCompat

abstract class RecordTileBase : TileService() {

    protected abstract fun isActive(): Boolean
    protected abstract fun elapsedMs(): Long
    protected abstract fun startAction(): String
    protected abstract fun stopAction(): String
    protected abstract fun labelActive(): String
    protected abstract fun labelIdle(): String
    protected abstract fun quickAction(): String

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = update()
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(RecordService.ACTION_STATE_CHANGED)
            addAction(RecordService.ACTION_RECORDING_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION", "UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onStartListening() = update()

    override fun onStopListening() = update()

    private fun update() {
        val tile = qsTile ?: return
        val active = isActive()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) labelActive() else labelIdle()
        tile.subtitle = if (active) RecordService.formatElapsed(elapsedMs()) else null
        tile.updateTile()
    }

    override fun onClick() {
        if (isActive()) {
            // 停止操作：进程活着时 startService 直接生效，无后台限制
            runCatching {
                startService(Intent(this, RecordService::class.java).setAction(stopAction()))
            }
            update()
            return
        }
        // 开始操作：ColorOS 等 ROM 对后台启动 FGS 是"静默丢弃"（不抛异常），
        // 直启不可靠。统一走 PendingIntent 豁免路径跳 App，由前台 Activity 启动，
        // 前台启动 100% 可靠
        val needPermission = if (startAction() == RecordService.ACTION_START_AUDIO) {
            checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        } else {
            checkSelfPermission(Manifest.permission.CAMERA)
        }
        if (needPermission != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "请先在 App 内授权后使用磁贴", Toast.LENGTH_LONG).show()
        }
        launchMain()
    }

    private fun launchMain() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_QUICK_ACTION, quickAction())
        }
        if (Build.VERSION.SDK_INT >= 34) {
            // PendingIntent 属于系统豁免路径，可绕过 ROM 的后台弹出界面限制
            val pi = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}

class RecordAudioTileService : RecordTileBase() {
    override fun isActive() = RecordService.audioRecording
    override fun elapsedMs() = RecordService.audioElapsedMs
    override fun startAction() = RecordService.ACTION_START_AUDIO
    override fun stopAction() = RecordService.ACTION_STOP_AUDIO
    override fun labelActive() = "停止录音"
    override fun labelIdle() = "录音"
    override fun quickAction() = "audio"
}

class RecordVideoTileService : RecordTileBase() {
    override fun isActive() = RecordService.videoRecording
    override fun elapsedMs() = RecordService.videoElapsedMs
    override fun startAction() = RecordService.ACTION_START_VIDEO
    override fun stopAction() = RecordService.ACTION_STOP_VIDEO
    override fun labelActive() = "停止录像"
    override fun labelIdle() = "录像"
    override fun quickAction() = "video"
}
