package com.translateoverlay.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private var isPanelVisible = false

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.CHINESE)
                .setTargetLanguage(TranslateLanguage.ENGLISH)
                .build()
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        downloadModel()
        showBubble()
    }

    private fun showBubble() {
        try {
            val inflater = LayoutInflater.from(this)
            bubbleView = inflater.inflate(R.layout.bubble_layout, null)

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 300
            }

            var startX = 0; var startY = 0
            var touchX = 0f; var touchY = 0f
            var dragging = false

            bubbleView!!.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        dragging = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - touchX).toInt()
                        val dy = (event.rawY - touchY).toInt()
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) dragging = true
                        params.x = startX + dx; params.y = startY + dy
                        windowManager.updateViewLayout(bubbleView, params); true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragging) togglePanel(); true
                    }
                    else -> false
                }
            }

            windowManager.addView(bubbleView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun togglePanel() {
        if (isPanelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        if (isPanelVisible) return
        isPanelVisible = true

        try {
            val inflater = LayoutInflater.from(this)
            panelView = inflater.inflate(R.layout.translation_panel, null)

            val params = WindowManager.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.85).toInt(),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            val etInput = panelView!!.findViewById<EditText>(R.id.etInput)
            val tvResult = panelView!!.findViewById<TextView>(R.id.tvResult)
            val btnTranslate = panelView!!.findViewById<Button>(R.id.btnTranslate)
            val btnScan = panelView!!.findViewById<Button>(R.id.btnScan)
            val btnClose = panelView!!.findViewById<ImageButton>(R.id.btnClose)

            btnClose.setOnClickListener { hidePanel() }

            btnTranslate.setOnClickListener {
                val text = etInput.text.toString().trim()
                if (text.isEmpty()) {
                    tvResult.text = "Type Chinese text above first"
                    return@setOnClickListener
                }
                tvResult.text = "Translating..."
                translateText(text) { result -> tvResult.text = result }
            }

            btnScan.setOnClickListener {
                tvResult.text = "Scanning screen..."
                hidePanel()
                Handler(Looper.getMainLooper()).postDelayed({
                    requestScreenCapture(tvResult)
                }, 600)
            }

            windowManager.addView(panelView, params)
        } catch (e: Exception) {
            e.printStackTrace()
            isPanelVisible = false
        }
    }

    private fun hidePanel() {
        isPanelVisible = false
        try {
            panelView?.let { windowManager.removeView(it) }
        } catch (e: Exception) { }
        panelView = null
    }

    private fun requestScreenCapture(resultView: TextView) {
        CaptureRequestActivity.pendingService = this
        CaptureRequestActivity.pendingResultView = resultView
        val intent = Intent(this, CaptureRequestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
    }

    fun onCaptureResult(bitmap: Bitmap, resultView: TextView) {
        runOCR(bitmap, resultView)
    }

    private fun runOCR(bitmap: Bitmap, resultView: TextView) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isEmpty()) {
                    showPanel()
                    resultView.text = "No Chinese text found on screen"
                } else {
                    translateText(text) { translation ->
                        showPanel()
                        resultView.text = translation
                    }
                }
            }
            .addOnFailureListener {
                showPanel()
                resultView.text = "OCR failed: ${it.message}"
            }
    }

    private fun translateText(text: String, onResult: (String) -> Unit) {
        translator.translate(text)
            .addOnSuccessListener { translated ->
                onResult("🇬🇧 $translated\n\n原文: $text")
            }
            .addOnFailureListener {
                onResult("Translation failed: ${it.message}\n\nTry again — model may still be downloading")
            }
    }

    private fun downloadModel() {
        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { bubbleView?.let { windowManager.removeView(it) } } catch (e: Exception) { }
        try { panelView?.let { windowManager.removeView(it) } } catch (e: Exception) { }
        try { recognizer.close() } catch (e: Exception) { }
        try { translator.close() } catch (e: Exception) { }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Translate Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Floating translate bubble" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingBubbleService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translate Overlay Running")
            .setContentText("Tap the bubble on screen to translate")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "translate_overlay"
        const val NOTIF_ID = 1
    }
}
