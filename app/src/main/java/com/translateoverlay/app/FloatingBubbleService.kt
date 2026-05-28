package com.translateoverlay.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
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
import kotlinx.coroutines.*

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val translatorOptions = TranslatorOptions.Builder()
        .setSourceLanguage(TranslateLanguage.CHINESE)
        .setTargetLanguage(TranslateLanguage.ENGLISH)
        .build()
    private val translator = Translation.getClient(translatorOptions)

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var isPanelVisible = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        downloadTranslationModel()
        showBubble()
    }

    // --- Bubble ---

    private fun showBubble() {
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.bubble_layout, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 200
        }

        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        var moved = false

        bubbleView!!.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    moved = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (Math.abs(dx) > 5 || Math.abs(dy) > 5) moved = true
                    params.x = initialX + dx; params.y = initialY + dy
                    windowManager.updateViewLayout(bubbleView, params); true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel(); true
                }
                else -> false
            }
        }

        windowManager.addView(bubbleView, params)
    }

    // --- Panel ---

    private fun togglePanel() {
        if (isPanelVisible) hidePanel() else showPanel()
    }

    private fun showPanel() {
        isPanelVisible = true
        val inflater = LayoutInflater.from(this)
        panelView = inflater.inflate(R.layout.translation_panel, null)

        val params = WindowManager.LayoutParams(
            dpToPx(300),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
            if (text.isEmpty()) { tvResult.text = "Enter Chinese text above"; return@setOnClickListener }
            tvResult.text = "Translating..."
            translateText(text) { result -> tvResult.text = result }
        }

        btnScan.setOnClickListener {
            tvResult.text = "Capturing screen..."
            hidePanel()
            Handler(Looper.getMainLooper()).postDelayed({ captureAndTranslate(tvResult) }, 500)
        }

        windowManager.addView(panelView, params)
    }

    private fun hidePanel() {
        isPanelVisible = false
        panelView?.let { windowManager.removeView(it); panelView = null }
    }

    // --- Screen Capture ---

    fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data)
    }

    private fun captureAndTranslate(resultView: TextView) {
        val mp = mediaProjection
        if (mp == null) {
            // Request screen capture permission via transparent activity
            val intent = Intent(this, CaptureRequestActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            CaptureRequestActivity.pendingResultView = resultView
            CaptureRequestActivity.pendingService = this
            startActivity(intent)
            return
        }
        doCapture(mp, resultView)
    }

    fun doCapture(mp: MediaProjection, resultView: TextView) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mp.createVirtualDisplay(
            "ScreenCapture", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            val image = imageReader?.acquireLatestImage()
            if (image == null) {
                showPanel()
                resultView.text = "Capture failed. Try again."
                return@postDelayed
            }
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * width
            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()
            virtualDisplay?.release()

            runOCR(bitmap, resultView)
        }, 300)
    }

    private fun runOCR(bitmap: Bitmap, resultView: TextView) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val detected = visionText.text.trim()
                if (detected.isEmpty()) {
                    showPanel()
                    resultView.text = "No Chinese text found on screen."
                    return@addOnSuccessListener
                }
                translateText(detected) { result ->
                    showPanel()
                    resultView.text = result
                }
            }
            .addOnFailureListener {
                showPanel()
                resultView.text = "OCR failed: ${it.message}"
            }
    }

    private fun translateText(text: String, onResult: (String) -> Unit) {
        translator.translate(text)
            .addOnSuccessListener { translated -> onResult("$translated\n\n(原文: $text)") }
            .addOnFailureListener { onResult("Translation error: ${it.message}") }
    }

    private fun downloadTranslationModel() {
        val conditions = com.google.mlkit.common.model.DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions)
    }

    // --- Lifecycle ---

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        bubbleView?.let { windowManager.removeView(it) }
        panelView?.let { windowManager.removeView(it) }
        virtualDisplay?.release()
        mediaProjection?.stop()
        recognizer.close()
        translator.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Helpers ---

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "Translate Overlay", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, FloatingBubbleService::class.java).apply { action = "STOP" }
        val stopPending = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Translate Overlay Active")
            .setContentText("Tap the bubble to translate Chinese text")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopSelf()
        return START_STICKY
    }

    companion object {
        const val CHANNEL_ID = "translate_overlay"
        const val NOTIF_ID = 1
    }
}
