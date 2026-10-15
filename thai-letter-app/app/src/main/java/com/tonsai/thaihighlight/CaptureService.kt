package com.tonsai.thaihighlight

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "com.tonsai.thaihighlight.START"
        const val ACTION_STOP = "com.tonsai.thaihighlight.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "resultData"
        private const val CHANNEL = "thai_letter_capture"
        private const val NOTIFICATION_ID = 502
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var worker: Handler
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var windowManager: WindowManager? = null
    private var overlay: HighlightOverlay? = null
    private val closing = AtomicBoolean(false)
    private val matcher = ShapeMatcher()
    private var frameAt = 0L
    private var fullWidth = 0
    private var fullHeight = 0
    private var lastGoodCell = -1
    private var repeatedCell = 0

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("ThaiLetterFrameAnalyzer").apply { start() }
        worker = Handler(thread.looper)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (projection != null) {
                    CaptureState.message = "กำลังทำงานอยู่ ถ้าต้องการเริ่มใหม่ ให้หยุดก่อน"
                    return START_NOT_STICKY
                }
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else intent.getParcelableExtra(EXTRA_DATA)
                if (code == 0 || resultData == null) {
                    CaptureState.message = "ไม่ได้รับสิทธิ์จับภาพหน้าจอ"
                    stopSelf()
                    return START_NOT_STICKY
                }
                val notification = notification()
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTIFICATION_ID, notification,
                            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else startForeground(NOTIFICATION_ID, notification)
                    startCapture(code, resultData)
                } catch (e: Exception) {
                    CaptureState.message = "เริ่มไม่ได้: ${e.localizedMessage}"
                    stopSelf()
                }
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(code: Int, token: Intent) {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        val metrics = DisplayMetrics().also { windowManager!!.defaultDisplay.getRealMetrics(it) }
        fullWidth = metrics.widthPixels
        fullHeight = metrics.heightPixels

        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, token)
        val p = projection ?: error("ไม่สามารถเปิด MediaProjection")
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                CaptureState.message = "สิทธิ์จับภาพสิ้นสุดแล้ว"
                main.post { stopSelf() }
            }
        }, worker)

        // Use the same full-screen coordinate system for screenshots and overlay.
        reader = ImageReader.newInstance(fullWidth, fullHeight, PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener({ source -> analyzeFrame(source) }, worker)
        display = p.createVirtualDisplay(
            "ThaiLetterHighlight", fullWidth, fullHeight, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, worker
        )

        overlay = HighlightOverlay(this)
        val lp = WindowManager.LayoutParams(
            fullWidth, fullHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Allow touches to pass through this non-touchable overlay on Android 12+.
            alpha = 0.7f
        }
        windowManager!!.addView(overlay, lp)
        CaptureState.running = true
        CaptureState.message = "รอหน้าจอเกมและอ่านตัวเป้าหมายสีส้ม…"
    }

    private fun analyzeFrame(source: ImageReader) {
        val img = try { source.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val now = SystemClock.uptimeMillis()
            if (closing.get() || now - frameAt < 30L) return
            frameAt = now
            val plane = img.planes.firstOrNull() ?: return
            val stride = plane.pixelStride
            val rowStride = plane.rowStride
            if (stride != 4 || rowStride < fullWidth * 4) {
                CaptureState.message = "รูปแบบพิกเซลไม่รองรับ (pixelStride=$stride)"
                return
            }
            val paddedWidth = rowStride / stride
            val bitmap = Bitmap.createBitmap(paddedWidth, fullHeight, Bitmap.Config.ARGB_8888)
            try {
                plane.buffer.rewind()
                bitmap.copyPixelsFromBuffer(plane.buffer)
                val cfg = RegionConfig.read(this)
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, fullWidth, fullHeight)
                val small = Bitmap.createScaledBitmap(cropped, 800, (fullHeight*800f/fullWidth).toInt(), true)
                val reading = try { matcher.scan(small, small.width, small.height, cfg) }
                    finally { if (small !== cropped) small.recycle(); if (cropped !== bitmap) cropped.recycle() }
                // Each frame stands on its own: never retain a previous target or wait for two frames.
                val accepted = reading.match
                CaptureState.message = reading.message
                val scaledRect = accepted?.rect?.let {
                    val scale = fullWidth/800f
                    android.graphics.Rect((it.left*scale).toInt(), (it.top*scale).toInt(),
                        (it.right*scale).toInt(), (it.bottom*scale).toInt())
                }
                val board = cfg.board.toPixels(fullWidth, fullHeight)
                val cue = cfg.cue.toPixels(fullWidth, fullHeight)
                main.post {
                    if (!closing.get()) overlay?.update(scaledRect, board, cue, cfg.showGuides)
                }
            } finally {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            CaptureState.message = "อ่านภาพไม่สำเร็จ: ${e.javaClass.simpleName} ${e.message ?: ""}"
        } finally {
            img.close()
        }
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "ตัวช่วยจับอักษรไทย", NotificationManager.IMPORTANCE_LOW)
        ch.description = "แสดงสถานะการจับภาพหน้าจอเพื่อหาพยัญชนะไทย"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).apply { action = ACTION_STOP }
        val stop = PendingIntent.getService(this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("กำลังจับตัวอักษร ก–ฮ")
            .setContentText("เทียบตัวส้มกับตาราง 20 ช่อง • แตะเพื่อเปิดการตั้งค่า")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.R.drawable.ic_media_pause, "หยุด", stop).build())
            .build()
    }

    override fun onDestroy() {
        if (closing.compareAndSet(false, true)) {
            CaptureState.running = false
            CaptureState.message = "หยุดจับภาพแล้ว"
            reader?.setOnImageAvailableListener(null, null)
            display?.release()
            display = null
            reader?.close()
            reader = null
            projection?.stop()
            projection = null
            overlay?.let { runCatching { windowManager?.removeViewImmediate(it) } }
            overlay = null
            if (::thread.isInitialized) thread.quitSafely()
        }
        super.onDestroy()
    }
}
