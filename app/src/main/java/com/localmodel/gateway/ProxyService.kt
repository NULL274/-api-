package com.localmodel.gateway

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking

class ProxyService : Service() {

    companion object {
        const val ACTION_START = "com.localmodel.gateway.action.START"
        const val ACTION_STOP = "com.localmodel.gateway.action.STOP"
        const val ACTION_RESTART = "com.localmodel.gateway.action.RESTART"

        private const val CHANNEL_ID = "proxy"
        private const val NOTIFICATION_ID = 1

        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var baseUrl: String? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ProxyService::class.java).setAction(ACTION_STOP),
            )
        }

        fun restart(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ProxyService::class.java).setAction(ACTION_RESTART),
            )
        }
    }

    private var server: ApiServer? = null
    private var overlay: View? = null

    private val windowManager: WindowManager by lazy {
        getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_RESTART -> {
                shutdown()
                startForeground(NOTIFICATION_ID, buildNotification("正在重启…"))
                startServer()
                return START_STICKY
            }
        }
        startForeground(NOTIFICATION_ID, buildNotification("正在启动…"))
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        val port = Prefs.serverPort
        val host = if (Prefs.serverLan) "0.0.0.0" else "127.0.0.1"
        val modelId = Prefs.modelName.ifBlank { "local-model" }
        val key = Prefs.apiKey.ifBlank { null }
        DiagLog.d("反代启动：bind=$host:$port 模型名=$modelId 鉴权=${if (key == null) "无" else "有"}")
        try {
            val created = ApiServer(host, port, key, modelId)
            created.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = created
            running = true
            baseUrl = if (Prefs.serverLan) NetUtils.lanBaseUrl(port) else NetUtils.loopbackBaseUrl(port)
            DiagLog.d("反代已就绪：$baseUrl")
            notify(buildNotification("服务运行中 · $baseUrl"))
            showOverlay()
            selfTest(port)
            ensureModelLoaded()
        } catch (e: Exception) {
            running = false
            baseUrl = null
            DiagLog.e("反代启动失败", e)
            notify(buildNotification("服务启动失败：${e.message}"))
            stopSelf()
        }
    }

    private fun selfTest(port: Int) {
        Thread {
            runCatching { Thread.sleep(600) }
            val result = runCatching {
                val connection = URL("http://127.0.0.1:$port/v1/models").openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.setRequestProperty("Authorization", "Bearer ${Prefs.apiKey}")
                val code = connection.responseCode
                connection.disconnect()
                code
            }
            DiagLog.d(
                "反代自检 http://127.0.0.1:$port/v1/models -> " +
                    (result.getOrNull()?.let { "HTTP $it" } ?: ("失败: ${result.exceptionOrNull()?.message}"))
            )
        }.start()
    }

    private fun ensureModelLoaded() {
        if (LlmEngine.isLoaded) return
        val path = Prefs.modelPath ?: return
        if (!File(path).exists()) {
            DiagLog.w("反代服务：模型文件不存在，无法自动加载 $path")
            return
        }
        Thread {
            if (LlmEngine.isLoaded) return@Thread
            DiagLog.d("反代服务：模型未加载，自动加载 $path")
            val result = runBlocking { LlmEngine.load(applicationContext, path, Prefs.engineParams()) }
            DiagLog.d(
                "反代服务自动加载结果: " +
                    (result.exceptionOrNull()?.let { "失败 ${it.message}" } ?: "成功 backend=${LlmEngine.activeBackend}")
            )
        }.start()
    }

    private fun shutdown() {
        DiagLog.d("反代服务停止（running=$running）")
        hideOverlay()
        runCatching { server?.stop() }
        server = null
        running = false
        baseUrl = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun showOverlay() {
        if (overlay != null || !Settings.canDrawOverlays(this)) return
        val density = resources.displayMetrics.density
        val size = (48 * density).toInt()

        val view = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF3D5AFE.toInt())
                setStroke((2 * density).toInt(), 0xCCFFFFFF.toInt())
            }
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (12 * density).toInt()
            y = (resources.displayMetrics.heightPixels * 0.35f).toInt()
        }

        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            private var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        dragged = false
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                        dragged = true
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        if (!dragged) v.performClick()
                        return true
                    }
                }
                return false
            }
        })
        view.setOnClickListener { openApp() }

        runCatching {
            windowManager.addView(view, params)
            overlay = view
        }
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop_server), stop)
            .build()
    }
}
