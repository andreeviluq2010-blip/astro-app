package com.example.astrohyperlapse

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class AstroCameraService : Service() {

    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var exposureTimeNs: Long = 15_000_000_000L
    private var isoValue: Int = 2000
    private var focusDistance: Float = 0.0f
    private var skyLinePct: Float = 58.0f
    private var groundLinePct: Float = 74.0f
    private var groundSuppression: Float = 0.80f
    private var isZenithMode: Boolean = false
    private var isRollingMode: Boolean = true
    private var stackTarget: Int = 16
    private var stretchFactor: Float = 180.0f
    private var dlssMultiplier: Int = 4
    private var bitrateMbps: Int = 100
    private var stopHour: Int = 4
    private var stopMinute: Int = 30

    private lateinit var reusableBitmap: Bitmap
    private lateinit var masterBitmap: Bitmap
    private var processorHandle: Long = 0L
    private var subFrameCount = 0
    private var masterCount = 0
    private var startTimeMs = 0L
    private lateinit var sessionDir: File
    private val masterFiles = mutableListOf<File>()
    private var isEncoding = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AstroHyperLapse::WakeLock")
        wakeLock.acquire(12 * 60 * 60 * 1000L)

        cameraThread = HandlerThread("AstroCameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        startForeground(1, createNotification("Ночной астро-регистратор активен..."))
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        sessionDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "AstroHyperLapse/Session_$timeStamp")
        sessionDir.mkdirs()
        startTimeMs = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_FINISH_AND_ENCODE") {
            finishAndEncodeVideo()
            return START_NOT_STICKY
        }

        exposureTimeNs = intent?.getLongExtra("EXPOSURE", 15_000_000_000L) ?: exposureTimeNs
        isoValue = (intent?.getIntExtra("ISO", 2000) ?: isoValue).coerceAtMost(3000)
        focusDistance = intent?.getFloatExtra("FOCUS", 0.0f) ?: focusDistance
        skyLinePct = intent?.getFloatExtra("SKY_LINE", 58.0f) ?: skyLinePct
        groundLinePct = intent?.getFloatExtra("GND_LINE", 74.0f) ?: groundLinePct
        groundSuppression = intent?.getFloatExtra("GROUND_SUPP", 0.80f) ?: groundSuppression
        isZenithMode = intent?.getBooleanExtra("ZENITH_MODE", false) ?: isZenithMode
        isRollingMode = intent?.getBooleanExtra("ROLLING_MODE", true) ?: isRollingMode
        stackTarget = intent?.getIntExtra("STACK_COUNT", 16) ?: stackTarget
        stretchFactor = intent?.getFloatExtra("STRETCH", 180.0f) ?: stretchFactor
        dlssMultiplier = intent?.getIntExtra("DLSS", 4) ?: dlssMultiplier
        bitrateMbps = intent?.getIntExtra("BITRATE", 100) ?: bitrateMbps
        stopHour = intent?.getIntExtra("STOP_HOUR", 4) ?: stopHour
        stopMinute = intent?.getIntExtra("STOP_MIN", 30) ?: stopMinute

        processorHandle = NativeAstroProcessor.initProcessor(
            skyLinePct, groundLinePct, stretchFactor, groundSuppression, isZenithMode, isRollingMode, stackTarget
        )

        reusableBitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)
        masterBitmap = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888)

        openCamera()
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (isEncoding) return
        cameraManager.openCamera("0", object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                startCaptureSession()
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                restartCameraWatchdog()
            }
        }, cameraHandler)
    }

    private fun startCaptureSession() {
        imageReader = ImageReader.newInstance(4000, 3000, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    resetWatchdog()
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)

                    val options = BitmapFactory.Options().apply {
                        inMutable = true
                        inBitmap = reusableBitmap
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                    subFrameCount++
                    val readyForMaster = NativeAstroProcessor.addFrame(processorHandle, reusableBitmap)

                    if (readyForMaster) {
                        NativeAstroProcessor.finalizeMasterFrame(processorHandle, masterBitmap)
                        saveMasterFrame(masterBitmap)
                        if (!isRollingMode) {
                            NativeAstroProcessor.resetBatch(processorHandle)
                        }
                    }

                    broadcastTelemetry("Съемка активна")
                    checkSunriseAutoStop()
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }

        val surfaces = listOf(imageReader!!.surface)
        cameraDevice?.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                val request = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
                    addTarget(imageReader!!.surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNs)
                    set(CaptureRequest.SENSOR_FRAME_DURATION, exposureTimeNs + 100_000_000L)
                    set(CaptureRequest.SENSOR_SENSITIVITY, isoValue.coerceAtMost(3000))
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance)
                }.build()

                session.setRepeatingRequest(request, null, cameraHandler)
                resetWatchdog()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {}
        }, cameraHandler)
    }

    private fun saveMasterFrame(bitmap: Bitmap) {
        masterCount++
        val file = File(sessionDir, String.format("Master_%04d.jpg", masterCount))
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        }
        masterFiles.add(file)
    }

    private fun checkSunriseAutoStop() {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        if (h == stopHour && m >= stopMinute && masterCount > 0 && !isEncoding) {
            finishAndEncodeVideo()
        }
    }

    private fun finishAndEncodeVideo() {
        if (isEncoding) return
        isEncoding = true
        watchdogJob?.cancel()
        captureSession?.close()
        cameraDevice?.close()

        serviceScope.launch {
            if (masterFiles.size >= 2) {
                val outMp4 = File(sessionDir, "AstroHyperLapse_4K_${bitrateMbps}Mbps.mp4")
                val encoder = UltraVideoEncoder()
                encoder.encodeHyperlapse(
                    masterFrames = masterFiles,
                    outputMp4 = outMp4,
                    bitrateMbps = bitrateMbps,
                    dlssMultiplier = dlssMultiplier
                ) { cur, total ->
                    broadcastTelemetry("DLSS Рендер: $cur / $total")
                }
                broadcastTelemetry("ГОТОВО! Сохранено: ${outMp4.name}")
            }
            stopSelf()
        }
    }

    private fun broadcastTelemetry(status: String) {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val currentNowUa = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMa = if (Math.abs(currentNowUa) > 10000) currentNowUa / 1000 else currentNowUa

        val battIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 300) ?: 300) / 10.0f
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val voltageMv = battIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3850) ?: 3850
        val elapsedSec = (System.currentTimeMillis() - startTimeMs) / 1000L

        // Оценка длины итогового видео с учетом DLSS при 30/60 FPS
        val totalVideoFrames = masterCount * dlssMultiplier
        val videoDurationSec = totalVideoFrames / 30.0f

        sendBroadcast(Intent("ASTRO_TELEMETRY_UPDATE").apply {
            setPackage(packageName)
            putExtra("MASTER_COUNT", masterCount)
            putExtra("SUB_COUNT", subFrameCount)
            putExtra("STACK_TARGET", stackTarget)
            putExtra("BATT_TEMP", temp)
            putExtra("BATT_LEVEL", level)
            putExtra("BATT_MA", currentMa)
            putExtra("BATT_MV", voltageMv)
            putExtra("ELAPSED_SEC", elapsedSec)
            putExtra("VIDEO_SEC", videoDurationSec)
            putExtra("STATUS", status)
        })
    }

    private fun resetWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            delay((exposureTimeNs / 1_000_000) + 15_000L)
            if (!isEncoding) restartCameraWatchdog()
        }
    }

    private fun restartCameraWatchdog() {
        captureSession?.close()
        cameraDevice?.close()
        Thread.sleep(2000)
        openCamera()
    }

    private fun createNotification(text: String): Notification {
        val channelId = "astro_channel"
        val channel = NotificationChannel(channelId, "Astro Capture", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AstroHyperLapse Ultra")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogJob?.cancel()
        serviceScope.cancel()
        captureSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        cameraThread?.quitSafely()
        if (processorHandle != 0L) NativeAstroProcessor.releaseProcessor(processorHandle)
        if (wakeLock.isHeld) wakeLock.release()
    }
}
