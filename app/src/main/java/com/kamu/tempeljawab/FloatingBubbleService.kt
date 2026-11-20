package com.kamu.tempeljawab

import android.annotation.SuppressLint
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
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.*
import android.webkit.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var params: WindowManager.LayoutParams

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopSelf()
        }
    }
    private var fileGambarTerakhir: Uri? = null

    private lateinit var webView: WebView
    private lateinit var kotakJawaban: LinearLayout

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mulaiNotifikasiForeground()

        if (mediaProjection != null) return START_NOT_STICKY
        if (intent == null || intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) != Activity.RESULT_OK) {
            stopSelf()
            return START_NOT_STICKY
        }
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("DATA")
        }

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = checkNotNull(projectionManager.getMediaProjection(Activity.RESULT_OK, data))
            mediaProjection = projection
            projection.registerCallback(projectionCallback, mainHandler)
            siapkanPerekamLayar(projection)
        } catch (e: SecurityException) {
            Log.w("FloatingBubbleService", "Izin perekaman layar tidak berlaku", e)
            stopSelf()
        } catch (e: IllegalStateException) {
            Log.w("FloatingBubbleService", "Sesi perekaman layar tidak tersedia", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    @SuppressLint("ClickableViewAccessibility", "SetJavaScriptEnabled")
    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_bubble, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        windowManager.addView(floatingView, params)

        val bubbleIcon = floatingView.findViewById<TextView>(R.id.bubbleIcon)
        kotakJawaban = floatingView.findViewById(R.id.kotakJawaban)
        webView = floatingView.findViewById(R.id.webViewAI)

        siapkanWebView()

        floatingView.findViewById<Button>(R.id.btnTutupWeb).setOnClickListener {
            kotakJawaban.visibility = View.GONE
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        bubbleIcon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    jepretDanKirim(params.x, params.y)
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun siapkanWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val gambar = fileGambarTerakhir
                fileGambarTerakhir = null
                filePathCallback?.onReceiveValue(gambar?.let { arrayOf(it) })
                return true
            }
        }

        webView.loadUrl("https://google.com")
    }

    private fun siapkanPerekamLayar(projection: MediaProjection) {
        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection.createVirtualDisplay(
            "ScreenCapture",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            null
        )
    }

    private fun jepretDanKirim(posX: Int, posY: Int) {
        val image = imageReader?.acquireLatestImage() ?: return
        val croppedBitmap = image.use {
            val plane = it.planes[0]
            val paddedWidth = plane.rowStride / plane.pixelStride
            val fullBitmap = Bitmap.createBitmap(paddedWidth, it.height, Bitmap.Config.ARGB_8888)
            fullBitmap.copyPixelsFromBuffer(plane.buffer)

            val cropX = (posX - 150).coerceIn(0, (it.width - 100).coerceAtLeast(0))
            val cropY = (posY - 100).coerceIn(0, (it.height - 100).coerceAtLeast(0))
            val cropW = minOf(600, it.width - cropX)
            val cropH = minOf(400, it.height - cropY)
            Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH).also { crop ->
                if (crop !== fullBitmap) fullBitmap.recycle()
            }
        }

        val file = File(cacheDir, "soal_scan.jpg")
        FileOutputStream(file).use { out ->
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        fileGambarTerakhir = Uri.fromFile(file)

        kotakJawaban.visibility = View.VISIBLE

        mainHandler.postDelayed({
            val jsOtomatis = """
                (function() {
                    var inputFile = document.querySelector('input[type="file"]');
                    if (inputFile) { inputFile.click(); }
                })();
            """.trimIndent()
            webView.evaluateJavascript(jsOtomatis, null)
        }, 500)
    }

    private fun mulaiNotifikasiForeground() {
        val channelId = "tempel_jawab_channel"
        val channel = NotificationChannel(channelId, "Tempel Jawab Service", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Tempel Jawab Aktif")
            .setContentText("Geser bubble ke soal untuk memindai")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .build()

        startForeground(1, notification)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
        mediaProjection?.stop()
        super.onDestroy()
    }
}
