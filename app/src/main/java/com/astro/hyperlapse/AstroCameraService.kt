package com.astro.hyperlapse

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class AstroCameraService : Service() {

    companion object {
        init {
            System.loadLibrary("astro_processor")
        }
        var isRunning = AtomicBoolean(false)
        var isStopping = AtomicBoolean(false)
        var subframesCaptured = 0
        var masterFramesRendered = 0
        var lastStatusText = "Ожидание старта..."
        var lastSavedPath = ""
    }

    external fun nativeInitEngine()
    external fun nativePushSubframe(
        yuvData: ByteArray, width: Int, height: Int,
        windowSize: Int, mode: Int, maskPercent: Int, starGain: Float, blackCut: Int
    ): Boolean
    external fun nativeClearWindowForStepMode()
    external fun nativeGenerateMasterAndInterpolate(dlssMultiplier: Int, callback: Any): Int

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var videoEncoder: VideoEncoder? = null

    private var exposureSec = 15
    private var isoVal = 2000
    private var windowSize = 16
    private var dlssMult = 4
    private var bitrateMbps = 100
    private var mode = 0
    private var focusDist = 0.0f
    private var maskPercent = 50
    private var starGain = 35f
    private var blackCut = 18

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_ASTRO") {
            stopRecordingAsync()
            return START_NOT_STICKY
        }

        exposureSec = intent?.getIntExtra("EXPOSURE", 15) ?: 15
        isoVal = intent?.getIntExtra("ISO", 2000) ?: 2000
        windowSize = intent?.getIntExtra("WINDOW", 16) ?: 16
        dlssMult = intent?.getIntExtra("DLSS", 4) ?: 4
        bitrateMbps = intent?.getIntExtra("BITRATE", 100) ?: 100
        mode = intent?.getIntExtra("MODE", 0) ?: 0
        focusDist = intent?.getFloatExtra("FOCUS", 0.0f) ?: 0.0f
        maskPercent = intent?.getIntExtra("MASK", 50) ?: 50
        starGain = intent?.getFloatExtra("GAIN", 35f) ?: 35f
        blackCut = intent?.getIntExtra("BLACK_CUT", 18) ?: 18

        startForegroundWithNotif()
        startRecordingEngine()
        return START_STICKY
    }

    private fun startForegroundWithNotif() {
        val chanId = "astro_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, "Astro Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
        val notif = NotificationCompat.Builder(this, chanId)
            .setContentTitle("AstroHyperLapse Ultra")
            .setContentText("Ночная съемка звезд активна...")
            .setSmallIcon(R.drawable.ic_astro_launcher)
            .build()
        startForeground(101, notif)
    }

    private fun startRecordingEngine() {
        if (isRunning.get()) return
        isRunning.set(true)
        isStopping.set(false)
        subframesCaptured = 0
        masterFramesRendered = 0
        lastStatusText = "Инициализация камеры и C++ ядра..."

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Astro::Lock").apply { acquire(10 * 3600 * 1000L) }

        bgThread = HandlerThread("AstroCamThread").apply { start() }
        bgHandler = Handler(bgThread!!.looper)

        bgHandler?.post {
            nativeInitEngine()
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(dir, "Astro_4K_${stamp}.mp4")
            lastSavedPath = outFile.absolutePath

            val targetFps = if (dlssMult >= 4) 60 else 30
            videoEncoder = VideoEncoder(outFile, 3840, 2160, bitrateMbps, targetFps).apply { start() }

            openCameraForMarathon()
        }
    }

    @SuppressWarnings("MissingPermission")
    private fun openCameraForMarathon() {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"

        imageReader = ImageReader.newInstance(3840, 2160, ImageFormat.YUV_420_888, 3).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                if (!isRunning.get() || isStopping.get()) {
                    img.close()
                    return@setOnImageAvailableListener
                }
                try {
                    val yuvBytes = imageToNV21(img)
                    val w = img.width
                    val h = img.height
                    img.close()

                    subframesCaptured++
                    lastStatusText = "Снят подкадр #$subframesCaptured (Сложение в C++...)"

                    val ready = nativePushSubframe(yuvBytes, w, h, windowSize, mode, maskPercent, starGain, blackCut)
                    if (ready && isRunning.get() && !isStopping.get()) {
                        val callback = object {
                            @Suppress("unused")
                            fun onEncodedFrameReady(data: ByteArray, fw: Int, fh: Int) {
                                if (isRunning.get() && !isStopping.get()) {
                                    videoEncoder?.encodeFrame(data)
                                }
                            }
                        }
                        nativeGenerateMasterAndInterpolate(dlssMult, callback)
                        masterFramesRendered++
                        lastStatusText = "Готов Мастер-кадр #$masterFramesRendered (Подкадров: $subframesCaptured)"
                        if (mode == 1) nativeClearWindowForStepMode()
                    }

                    if (isRunning.get() && !isStopping.get()) {
                        triggerSingleLongExposure()
                    }
                } catch (e: Exception) {
                    try { img.close() } catch (_: Exception) {}
                }
            }, bgHandler)
        }

        try {
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    val surface = imageReader!!.surface
                    camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            captureSession = session
                            lastStatusText = "Съемка 1-го подкадра (${exposureSec} сек)..."
                            triggerSingleLongExposure()
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) {}
                    }, bgHandler)
                }
                override fun onDisconnected(camera: CameraDevice) {}
                override fun onError(camera: CameraDevice, error: Int) {}
            }, bgHandler)
        } catch (_: Exception) {}
    }

    private fun triggerSingleLongExposure() {
        val dev = cameraDevice ?: return
        val sess = captureSession ?: return
        if (!isRunning.get() || isStopping.get()) return
        try {
            val req = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(imageReader!!.surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureSec * 1_000_000_000L)
                set(CaptureRequest.SENSOR_SENSITIVITY, isoVal)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDist)
                set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }
            sess.capture(req.build(), null, bgHandler)
        } catch (_: Exception) {}
    }

    private fun imageToNV21(image: android.media.Image): ByteArray {
        val w = image.width
        val h = image.height
        val ySize = w * h
        val uvSize = w * h / 2
        val nv21 = ByteArray(ySize + uvSize)
        val yBuf = image.planes[0].buffer
        val uBuf = image.planes[1].buffer
        val vBuf = image.planes[2].buffer

        var pos = 0
        val yRowStride = image.planes[0].rowStride
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(nv21, pos, w)
            pos += w
        }
        val uvRowStride = image.planes[2].rowStride
        val uvPixelStride = image.planes[2].pixelStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val idx = row * uvRowStride + col * uvPixelStride
                nv21[pos++] = vBuf.get(idx)
                nv21[pos++] = uBuf.get(idx)
            }
        }
        return nv21
    }

    // Безопасная асинхронная остановка в отдельном потоке, чтобы интерфейс никогда не зависал!
    private fun stopRecordingAsync() {
        if (isStopping.getAndSet(true)) return
        isRunning.set(false)
        lastStatusText = "Остановка затвора и сохранение видео..."

        Thread {
            try { captureSession?.abortCaptures() } catch (_: Exception) {}
            try { captureSession?.close() } catch (_: Exception) {}
            captureSession = null

            try { cameraDevice?.close() } catch (_: Exception) {}
            cameraDevice = null

            try { imageReader?.close() } catch (_: Exception) {}
            imageReader = null

            val saved = videoEncoder?.stopAndRelease() ?: false
            videoEncoder = null

            try { bgThread?.quitSafely() } catch (_: Exception) {}
            bgThread = null

            try {
                if (wakeLock?.isHeld == true) wakeLock?.release()
            } catch (_: Exception) {}

            lastStatusText = if (saved) {
                "Видео успешно сохранено: $lastSavedPath"
            } else {
                "Остановлено (0 мастер-кадров — видео не создано)"
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.start()
    }

    override fun onDestroy() {
        stopRecordingAsync()
        super.onDestroy()
    }
}
