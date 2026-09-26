package com.example.astrohyperlapse

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    // --- НАВИГАЦИЯ МЕЖДУ 4 ЭКРАНАМИ ---
    private lateinit var rootContainer: FrameLayout
    private lateinit var screen1Dashboard: LinearLayout
    private lateinit var screen2Calibration: FrameLayout
    private lateinit var screen3Settings: ScrollView
    private lateinit var screen4Stealth: FrameLayout

    // Элементы Экрана 1 (Диагностика)
    private lateinit var tvDiagBattery: TextView
    private lateinit var tvDiagStorage: TextView

    // Элементы Экрана 2 (Калибровка + Превью + Пробное фото)
    private lateinit var previewView: TextureView
    private lateinit var overlayView: CalibrationOverlayView
    private lateinit var btnLoupe: Button
    private lateinit var btnGrid: Button
    private lateinit var btnScenario: Button
    private lateinit var btnTestPhoto: Button
    private lateinit var testResultContainer: FrameLayout
    private lateinit var ivTestPreview: ImageView
    private lateinit var testAdjustPanel: LinearLayout

    // Элементы Экрана 4 (Стелс-запись)
    private lateinit var panelActive4: LinearLayout
    private lateinit var tvActiveStatus: TextView
    private lateinit var tvStealthLissajous: TextView
    private lateinit var btnAbortRecord: Button
    private lateinit var btnSleepNow: Button

    // Параметры съемки и кастомизации
    private var isZenithMode = false
    private var skyLinePct = 55.0f      // Зеленая линия (выше нее буст звезд)
    private var groundLinePct = 72.0f   // Красная линия (ниже нее щит от фонаря)
    private var groundSuppression = 0.80f
    private var stretchGain = 180.0f
    private var focusVal = 0.0f
    private var exposureSec = 15
    private var isoVal = 2000
    private var isRollingMode = true
    private var stackFrames = 16        // 16 * 15с = 4 мин света
    private var dlssMult = 4
    private var bitrateMbps = 100
    private var stealthDelayMin = 10    // Через 10 мин гасим экран
    private var lissajousRadiusPx = 6f  // Амплитуда плавания текста 1-10 px
    private var stealthTextColor = Color.parseColor("#770000") // Темно-красный

    // Служебные переменные
    private var previewCamera: CameraDevice? = null
    private var previewSession: CameraCaptureSession? = null
    private var testCaptureReader: ImageReader? = null
    private var testProcessorHandle = 0L
    private var testRawBitmap: Bitmap? = null
    private var testOutputBitmap: Bitmap? = null

    private var sensorManager: SensorManager? = null
    private var pitchAngle = 0.0f
    private var rollAngle = 0.0f
    private var gridMode = 1 // 0=Выкл, 1=3x3, 2=Полярные круги
    private var loupe10x = false
    private var isRecording = false
    private var isStealthSleeping = false

    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stealthTimerJob: Job? = null
    private var lissajousJob: Job? = null

    // Прием телеметрии от сервиса камеры
    private val telemetryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val master = intent?.getIntExtra("MASTER_COUNT", 0) ?: 0
            val sub = intent?.getIntExtra("SUB_COUNT", 0) ?: 0
            val target = intent?.getIntExtra("STACK_TARGET", 16) ?: 16
            val temp = intent?.getFloatExtra("BATT_TEMP", 30.0f) ?: 30.0f
            val level = intent?.getIntExtra("BATT_LEVEL", 100) ?: 100
            val ma = intent?.getIntExtra("BATT_MA", 0) ?: 0
            val elapsedSec = intent?.getLongExtra("ELAPSED_SEC", 0L) ?: 0L
            val videoSec = intent?.getFloatExtra("VIDEO_SEC", 0.0f) ?: 0.0f
            val status = intent?.getStringExtra("STATUS") ?: "Съемка"

            val sign = if (ma >= 0) "+" else ""
            val h = elapsedSec / 3600
            val m = (elapsedSec % 3600) / 60
            val s = elapsedSec % 60
            val timeStr = String.format("%02d:%02d:%02d", h, m, s)
            val vidMin = (videoSec / 60).toInt()
            val vidSec = (videoSec % 60).toInt()
            val videoLenStr = String.format("%02d мин %02d сек", vidMin, vidSec)

            // Обновляем дневную панель Экрана 4
            tvActiveStatus.text = String.format(
                "Статус: %s\nВремя съемки: %s\nМастер-кадров готово: %d (подкадр %d/%d)\nДлина видео (DLSS %dx): ~%s\nБатарея: %d%% (%s%d мА) | Температура: %.1f°C",
                status, timeStr, master, sub, target, dlssMult, videoLenStr, level, sign, ma, temp
            )

            // Обновляем ночной плавающий стелс-текст
            tvStealthLissajous.text = String.format(
                "⏱ Время: %s  |  🎬 Видео: ~%s\n🔋 %d%% (%s%d мА)  |  🌡 %.1f°C  |  Кадр #%d",
                timeStr, videoLenStr, level, sign, ma, temp, master
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("astro_prefs", Context.MODE_PRIVATE)
        focusVal = prefs.getFloat("saved_focus", 0.0f)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager

        rootContainer = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        buildScreen1Dashboard()
        buildScreen2Calibration()
        buildScreen3Settings()
        buildScreen4Stealth()

        rootContainer.addView(screen1Dashboard)
        rootContainer.addView(screen2Calibration)
        rootContainer.addView(screen3Settings)
        rootContainer.addView(screen4Stealth)

        setContentView(rootContainer)
        showScreen(1)
        checkPermissions()
    }

    private fun showScreen(screenNum: Int) {
        screen1Dashboard.visibility = if (screenNum == 1) View.VISIBLE else View.GONE
        screen2Calibration.visibility = if (screenNum == 2) View.VISIBLE else View.GONE
        screen3Settings.visibility = if (screenNum == 3) View.VISIBLE else View.GONE
        screen4Stealth.visibility = if (screenNum == 4) View.VISIBLE else View.GONE

        if (screenNum == 1) updateScreen1Diagnostics()
        if (screenNum == 2) {
            if (previewView.isAvailable) startPreviewCamera()
        } else {
            if (!isRecording) stopPreviewCamera()
        }
    }

    // =========================================================================
    // ЭКРАН 1: ПРЕДПОЛЕТНЫЙ ДАШБОРД (ДИАГНОСТИКА КАК В AIDA64)
    // =========================================================================
    private fun buildScreen1Dashboard() {
        screen1Dashboard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 60, 48, 48)
            setBackgroundColor(Color.parseColor("#080808"))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val tvTitle = TextView(this).apply {
            text = "ASTROHYPERLAPSE ULTRA"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val tvSub = TextView(this).apply {
            text = "Samsung Galaxy S22 Ultra • Ночной комбайн"
            setTextColor(Color.parseColor("#888888"))
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 4, 0, 40)
        }
        screen1Dashboard.addView(tvTitle)
        screen1Dashboard.addView(tvSub)

        val cardDiag = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(32, 28, 32, 28)
        }

        val tvHeader = TextView(this).apply {
            text = "📊 ПРЕДПОЛЕТНАЯ ДИАГНОСТИКА"
            setTextColor(Color.parseColor("#44AAFF"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 16)
        }
        cardDiag.addView(tvHeader)

        tvDiagBattery = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setLineSpacing(8f, 1f)
        }
        cardDiag.addView(tvDiagBattery)

        tvDiagStorage = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 14f
            setPadding(0, 16, 0, 0)
        }
        cardDiag.addView(tvDiagStorage)
        screen1Dashboard.addView(cardDiag)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }
        screen1Dashboard.addView(spacer)

        val btnStartFlow = Button(this).apply {
            text = "🚀 НАЧАТЬ ЗАПИСЬ (ПРИЦЕЛ И ФОКУС) ➔"
            setBackgroundColor(Color.parseColor("#881111"))
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding(0, 36, 0, 36)
            setOnClickListener { showScreen(2) }
        }
        screen1Dashboard.addView(btnStartFlow)
    }

    private fun updateScreen1Diagnostics() {
        val bm = getSystemService(Context.POWER_SERVICE)
        val battManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val curUa = battManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val curMa = if (Math.abs(curUa) > 10000) curUa / 1000 else curUa

        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val battIntent = registerReceiver(null, filter)
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val temp = (battIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 320) ?: 320) / 10.0f
        val voltMv = battIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 3812) ?: 3812
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val sign = if (curMa >= 0) "+" else ""
        val powerState = if (isCharging) "🔌 Питание подключено (AC/Повербанк)" else "⚠️ Внимание: Работа от батареи!"

        tvDiagBattery.text = String.format(
            "%s\n• Заряд: %d%%  |  Ток: %s%d мА  |  Напряжение: %.3f V\n• Температура батареи: %.1f°C (Норма до 38°C)",
            powerState, level, sign, curMa, voltMv / 1000.0f, temp
        )

        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
        val freeGb = freeBytes / (1024.0 * 1024.0 * 1024.0)
        val hoursLeft = (freeGb / 0.8).toInt() // ~0.8 ГБ в час при 100% JPEG Master
        tvDiagStorage.text = String.format(
            "💾 Память телефона: Свободно %.1f ГБ\n• Хватит на ~%d часов непрерывного астро-таймлапса",
            freeGb, hoursLeft
        )
    }

    // =========================================================================
    // ЭКРАН 2: ПРИЦЕЛ, ГИРОСКОП, 2 ЛИНИИ И ЖИВОЕ ПРОБНОЕ АСТРО-ФОТО
    // =========================================================================
    private fun buildScreen2Calibration() {
        screen2Calibration = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // 1. Видоискатель
        previewView = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (screen2Calibration.visibility == View.VISIBLE && !isRecording) startPreviewCamera()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        screen2Calibration.addView(previewView)

        // 2. Интерактивный оверлей (Две линии + Гироскоп + Сетка)
        overlayView = CalibrationOverlayView(this).apply {
            onLinesChanged = { sky, gnd ->
                skyLinePct = sky
                groundLinePct = gnd
            }
        }
        screen2Calibration.addView(overlayView)

        // 3. Верхняя плашка кнопок
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(16, 12, 16, 12)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }

        btnScenario = Button(this).apply {
            text = "🏠 Из окна"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#552222"))
            setOnClickListener {
                isZenithMode = !isZenithMode
                text = if (isZenithMode) "🌌 Зенит 90°" else "🏠 Из окна"
                overlayView.isZenith = isZenithMode
                overlayView.invalidate()
            }
        }
        topBar.addView(btnScenario)

        btnGrid = Button(this).apply {
            text = "Сетка: 3x3"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#333333"))
            setOnClickListener {
                gridMode = (gridMode + 1) % 3
                text = when (gridMode) {
                    1 -> "Сетка: 3x3"
                    2 -> "Полярная"
                    else -> "Сетка: Выкл"
                }
                overlayView.gridType = gridMode
                overlayView.invalidate()
            }
        }
        topBar.addView(btnGrid)

        btnLoupe = Button(this).apply {
            text = "🔍 Лупа 1x"
            textSize = 12f
            setTextColor(Color.RED)
            setBackgroundColor(Color.parseColor("#330000"))
            setOnClickListener {
                loupe10x = !loupe10x
                text = if (loupe10x) "🔍 10x ВКЛ" else "🔍 Лупа 1x"
                val m = Matrix()
                if (loupe10x) m.postScale(10f, 10f, previewView.width / 2f, previewView.height / 2f)
                previewView.setTransform(m)
                overlayView.isLoupe = loupe10x
                overlayView.invalidate()
            }
        }
        topBar.addView(btnLoupe)
        screen2Calibration.addView(topBar)

        // 4. Нижняя панель фокуса и кнопка Пробного Фото
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC0D0D0D"))
            setPadding(32, 16, 32, 24)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }

        val tvFocus = TextView(this).apply {
            setTextColor(Color.parseColor("#FF6666"))
            textSize = 13f
            text = String.format("Фокус по звезде: %.3f (Тяни до резкой точки)", focusVal)
        }
        val sbFocus = SeekBar(this).apply {
            max = 500
            progress = (focusVal * 1000).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    focusVal = p / 1000.0f
                    tvFocus.text = String.format("Фокус по звезде: %.3f (Тяни до резкой точки)", focusVal)
                    getSharedPreferences("astro_prefs", Context.MODE_PRIVATE).edit().putFloat("saved_focus", focusVal).apply()
                    updatePreviewFocus()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        bottomBar.addView(tvFocus)
        bottomBar.addView(sbFocus)

        val rowBtns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 0)
        }
        val btnBack1 = Button(this).apply {
            text = "⬅ Назад"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnClickListener { showScreen(1) }
        }
        btnTestPhoto = Button(this).apply {
            text = "✨ СДЕЛАТЬ ПРОБНОЕ АСТРО-ФОТО"
            setBackgroundColor(Color.parseColor("#661111"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.3f)
            setOnClickListener { executeQuickTestShot() }
        }
        rowBtns.addView(btnBack1)
        rowBtns.addView(btnTestPhoto)
        bottomBar.addView(rowBtns)
        screen2Calibration.addView(bottomBar)

        // 5. Всплывающий контейнер результатов Пробного Фото с ЖИВОЙ ДОКРУТКОЙ
        buildTestResultOverlay()
        screen2Calibration.addView(testResultContainer)
    }

    private fun buildTestResultOverlay() {
        testResultContainer = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        ivTestPreview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        testResultContainer.addView(ivTestPreview)

        testAdjustPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD111111"))
            setPadding(32, 20, 32, 24)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }

        val tvHead = TextView(this).apply {
            text = "🎨 ЖИВАЯ ДОКРУТКА ПРОБНОГО ФОТО (Без повторной съемки)"
            setTextColor(Color.parseColor("#FFCC44"))
            textSize = 13.5f
            typeface = Typeface.DEFAULT_BOLD
        }
        testAdjustPanel.addView(tvHead)

        val tvGain = TextView(this).apply {
            setTextColor(Color.parseColor("#FF8888"))
            textSize = 12f
            text = "Яркость звезд (Gain): ${stretchGain.toInt()}"
        }
        val sbGain = SeekBar(this).apply {
            max = 450
            progress = (stretchGain - 50).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    stretchGain = (p + 50).toFloat()
                    tvGain.text = "Яркость звезд (Gain): ${stretchGain.toInt()}"
                    redevelopTestFrameLive()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        testAdjustPanel.addView(tvGain)
        testAdjustPanel.addView(sbGain)

        val tvShield = TextView(this).apply {
            setTextColor(Color.parseColor("#88CCFF"))
            textSize = 12f
            text = "Подавление фонаря соседа: ${(groundSuppression * 100).toInt()}%"
        }
        val sbShield = SeekBar(this).apply {
            max = 95
            progress = (groundSuppression * 100).toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    groundSuppression = p / 100.0f
                    tvShield.text = "Подавление фонаря соседа: $p%"
                    redevelopTestFrameLive()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        testAdjustPanel.addView(tvShield)
        testAdjustPanel.addView(sbShield)

        val rowAct = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val btnRetake = Button(this).apply {
            text = "🔄 Переснять"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnClickListener {
                testResultContainer.visibility = View.GONE
                if (!isRecording) startPreviewCamera()
            }
        }
        val btnAccept = Button(this).apply {
            text = "🔥 КАДР ИДЕАЛЕН! К НАСТРОЙКАМ ➔"
            setBackgroundColor(Color.parseColor("#881111"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.2f)
            setOnClickListener {
                testResultContainer.visibility = View.GONE
                showScreen(3)
            }
        }
        rowAct.addView(btnRetake)
        rowAct.addView(btnAccept)
        testAdjustPanel.addView(rowAct)
        testResultContainer.addView(testAdjustPanel)
    }

    private fun executeQuickTestShot() {
        btnTestPhoto.isEnabled = false
        btnTestPhoto.text = "⏳ Съемка 3 тестовых кадров..."
        stopPreviewCamera()

        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        testCaptureReader = ImageReader.newInstance(1920, 1440, ImageFormat.JPEG, 2)
        testProcessorHandle = NativeAstroProcessor.initProcessor(
            skyLinePct, groundLinePct, stretchGain, groundSuppression, isZenithMode, false, 3
        )
        testOutputBitmap = Bitmap.createBitmap(1920, 1440, Bitmap.Config.ARGB_8888)

        var taken = 0
        testCaptureReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                NativeAstroProcessor.addFrame(testProcessorHandle, bmp)
                taken++
                mainScope.launch {
                    btnTestPhoto.text = "⏳ Склейка и выравнивание ($taken/3)..."
                    if (taken >= 3) {
                        NativeAstroProcessor.finalizeMasterFrame(testProcessorHandle, testOutputBitmap!!)
                        ivTestPreview.setImageBitmap(testOutputBitmap)
                        testResultContainer.visibility = View.VISIBLE
                        btnTestPhoto.isEnabled = true
                        btnTestPhoto.text = "✨ СДЕЛАТЬ ПРОБНОЕ АСТРО-ФОТО"
                    }
                }
            } finally {
                img.close()
            }
        }, null)

        cm.openCamera("0", object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                previewCamera = camera
                val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_MANUAL).apply {
                    addTarget(testCaptureReader!!.surface)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_500_000_000L) // 1.5 сек на кадр для быстрого теста
                    set(CaptureRequest.SENSOR_SENSITIVITY, isoVal.coerceAtMost(3000))
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusVal)
                }.build()

                camera.createCaptureSession(listOf(testCaptureReader!!.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        sess.setRepeatingRequest(req, null, null)
                    }
                    override fun onConfigureFailed(sess: CameraCaptureSession) {}
                }, null)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, null)
    }

    private fun redevelopTestFrameLive() {
        if (testProcessorHandle != 0L && testOutputBitmap != null) {
            NativeAstroProcessor.updateLiveParams(
                testProcessorHandle, skyLinePct, groundLinePct, stretchGain, groundSuppression, isZenithMode
            )
            NativeAstroProcessor.finalizeMasterFrame(testProcessorHandle, testOutputBitmap!!)
            ivTestPreview.invalidate()
        }
    }

    // =========================================================================
    // ЭКРАН 3: НАСТРОЙКИ СЕРИИ И КАСТОМИЗАЦИЯ СТЕЛС-ЭКРАНА
    // =========================================================================
    private fun buildScreen3Settings() {
        screen3Settings = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0A0A0A"))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 48)
        }

        val tvHead = TextView(this).apply {
            text = "⚙️ НАСТРОЙКИ МАРАФОНА СЪЕМКИ"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, 24)
        }
        container.addView(tvHead)

        fun addSliderRow(title: String, maxVal: Int, initVal: Int, fmt: (Int) -> String, onCh: (Int) -> Unit) {
            val tv = TextView(this).apply {
                setTextColor(Color.parseColor("#FF7777"))
                textSize = 13.5f
                setPadding(0, 14, 0, 2)
                text = "$title: ${fmt(initVal)}"
            }
            val sb = SeekBar(this).apply {
                max = maxVal
                progress = initVal
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        tv.text = "$title: ${fmt(p)}"
                        onCh(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            container.addView(tv)
            container.addView(sb)
        }

        // Переключатель режима: Скользящий (Rolling 3-5 мин видео) vs Пакетный
        val btnMode = Button(this).apply {
            text = "🎬 РЕЖИМ: СКОЛЬЗЯЩИЙ (Длинное видео 3–5 минут)"
            setBackgroundColor(Color.parseColor("#441133"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isRollingMode = !isRollingMode
                text = if (isRollingMode) "🎬 РЕЖИМ: СКОЛЬЗЯЩИЙ (Длинное видео 3–5 минут)" else "⏱ РЕЖИМ: ПАКЕТНЫЙ (Быстрый пролет 30 сек)"
            }
        }
        container.addView(btnMode)

        addSliderRow("Выдержка одного подкадра", 25, exposureSec - 5, { "${it + 5} сек" }) { exposureSec = it + 5 }
        addSliderRow("ISO (Сенсор 1x, лимит 3000)", 2600, isoVal - 400, { "ISO ${it + 400}" }) { isoVal = (it + 400).coerceAtMost(3000) }
        addSliderRow("Длина выдержки Мастер-кадра", 28, stackFrames - 4, {
            val f = it + 4; val sec = f * exposureSec
            "$f подкадров (~${sec / 60} мин ${sec % 60} сек света)"
        }) { stackFrames = it + 4 }

        addSliderRow("Астро-DLSS (Генератор плавности)", 3, 2, {
            when (it) { 0 -> "1x (Без дорисовки)"; 1 -> "2x (Плавность x2)"; 2 -> "4x (DLSS 60 FPS)"; else -> "8x (Ультра)" }
        }) { dlssMult = when (it) { 0 -> 1; 1 -> 2; 2 -> 4; else -> 8 } }

        addSliderRow("Битрейт 4K HEVC видео", 100, bitrateMbps - 50, { "${it + 50} Мбит/с (Без квадратов)" }) { bitrateMbps = it + 50 }

        // --- БЛОК КАСТОМИЗАЦИИ СТЕЛС-ЭКРАНА ---
        val tvStealthHdr = TextView(this).apply {
            text = "\n🛡️ ЗАЩИТА ЭКРАНА И МАТРИЦЫ ОТ НАГРЕВА"
            setTextColor(Color.parseColor("#FFAA44"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 16, 0, 8)
        }
        container.addView(tvStealthHdr)

        addSliderRow("Время до полного гашения экрана", 29, stealthDelayMin - 1, { "${it + 1} минут" }) { stealthDelayMin = it + 1 }
        addSliderRow("Радиус плавания текста Лиссажу", 9, (lissajousRadiusPx - 1).toInt(), { "${it + 1} пикселей (Защита AMOLED)" }) { lissajousRadiusPx = (it + 1).toFloat() }

        val rowNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 32, 0, 0)
        }
        val btnBack2 = Button(this).apply {
            text = "⬅ Назад"
            setBackgroundColor(Color.parseColor("#333333"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            setOnClickListener { showScreen(2) }
        }
        val btnLaunch = Button(this).apply {
            text = "🌙 ЗАПУСТИТЬ НОЧНОЙ ГИПЕРЛАПС"
            setBackgroundColor(Color.parseColor("#880000"))
            setTextColor(Color.WHITE)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.2f)
            setOnClickListener { startFinalNightSession() }
        }
        rowNav.addView(btnBack2)
        rowNav.addView(btnLaunch)
        container.addView(rowNav)

        screen3Settings.addView(container)
    }

    // =========================================================================
    // ЭКРАН 4: НОЧНОЙ СТЕЛС-ПОЛЕТ (10 МИНУТ ПАНЕЛЬ -> ЧЕРНЫЙ ЭКРАН С ЛИССАЖУ)
    // =========================================================================
    private fun buildScreen4Stealth() {
        screen4Stealth = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Фаза 1: Контрольная панель первых 10 минут
        panelActive4 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F0F0F"))
            setPadding(40, 60, 40, 40)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val tvTitle4 = TextView(this).apply {
            text = "🚀 НОЧНАЯ СЪЕМКА АКТИВНА"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        val tvSub4 = TextView(this).apply {
            text = "Экран автоматически погаснет для защиты от нагрева матрицы"
            setTextColor(Color.GRAY)
            textSize = 12f
            setPadding(0, 4, 0, 32)
        }
        panelActive4.addView(tvTitle4)
        panelActive4.addView(tvSub4)

        tvActiveStatus = TextView(this).apply {
            setTextColor(Color.parseColor("#44FF88"))
            textSize = 14f
            setLineSpacing(8f, 1f)
            text = "Инициализация камеры и сервиса..."
        }
        panelActive4.addView(tvActiveStatus)

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
        }
        panelActive4.addView(spacer)

        btnSleepNow = Button(this).apply {
            text = "🌑 Погасить экран сейчас (Не ждать ${stealthDelayMin} мин)"
            setBackgroundColor(Color.parseColor("#222222"))
            setTextColor(Color.LTGRAY)
            setOnClickListener { enterStealthSleep() }
        }
        btnAbortRecord = Button(this).apply {
            text = "🛑 ПРЕРВАТЬ СВЯЗЬ / ЗАПИСЬ"
            setBackgroundColor(Color.parseColor("#660000"))
            setTextColor(Color.WHITE)
            setOnClickListener { abortSession() }
        }
        panelActive4.addView(btnSleepNow)
        panelActive4.addView(btnAbortRecord)
        screen4Stealth.addView(panelActive4)

        // Фаза 2: Ночной текст, плавающий по кривой Лиссажу (1-10 px)
        tvStealthLissajous = TextView(this).apply {
            setTextColor(stealthTextColor)
            textSize = 13.5f
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            text = "Запуск стелс-мониторинга..."
        }
        screen4Stealth.addView(tvStealthLissajous)

        // Двойной тап для пробуждения экрана ночью
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isStealthSleeping) exitStealthSleep()
                return true
            }
        })
        screen4Stealth.setOnTouchListener { _, ev -> gd.onTouchEvent(ev); true }
    }

    private fun startFinalNightSession() {
        showScreen(4)
        isRecording = true
        isStealthSleeping = false
        panelActive4.visibility = View.VISIBLE
        tvStealthLissajous.visibility = View.GONE

        val intent = Intent(this, AstroCameraService::class.java).apply {
            putExtra("EXPOSURE", exposureSec * 1_000_000_000L)
            putExtra("ISO", isoVal.coerceAtMost(3000))
            putExtra("FOCUS", focusVal)
            putExtra("SKY_LINE", skyLinePct)
            putExtra("GND_LINE", groundLinePct)
            putExtra("GROUND_SUPP", groundSuppression)
            putExtra("ZENITH_MODE", isZenithMode)
            putExtra("ROLLING_MODE", isRollingMode)
            putExtra("STACK_COUNT", stackFrames)
            putExtra("STRETCH", stretchGain)
            putExtra("DLSS", dlssMult)
            putExtra("BITRATE", bitrateMbps)
        }
        ContextCompat.startForegroundService(this, intent)

        // Запуск таймера гашения экрана
        stealthTimerJob?.cancel()
        stealthTimerJob = mainScope.launch {
            delay(stealthDelayMin * 60_000L)
            enterStealthSleep()
        }
    }

    private fun enterStealthSleep() {
        isStealthSleeping = true
        panelActive4.visibility = View.GONE
        tvStealthLissajous.visibility = View.VISIBLE

        val attrs = window.attributes
        attrs.screenBrightness = 0.01f
        window.attributes = attrs
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Аниматор Лиссажу (плавный дрейф 1-10 px для исключения выгорания AMOLED)
        lissajousJob?.cancel()
        lissajousJob = mainScope.launch {
            var t = 0f
            while (isActive) {
                val dx = lissajousRadiusPx * sin(0.7f * t)
                val dy = lissajousRadiusPx * cos(1.1f * t)
                tvStealthLissajous.translationX = dx
                tvStealthLissajous.translationY = dy
                t += 0.08f
                delay(100) // Плавное смещение 10 раз в секунду
            }
        }
    }

    private fun exitStealthSleep() {
        isStealthSleeping = false
        lissajousJob?.cancel()
        tvStealthLissajous.visibility = View.GONE
        panelActive4.visibility = View.VISIBLE

        val attrs = window.attributes
        attrs.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = attrs

        // Перезапуск таймера сна
        stealthTimerJob?.cancel()
        stealthTimerJob = mainScope.launch {
            delay(stealthDelayMin * 60_000L)
            enterStealthSleep()
        }
    }

    private fun abortSession() {
        val intent = Intent(this, AstroCameraService::class.java).apply {
            action = "ACTION_FINISH_AND_ENCODE"
        }
        ContextCompat.startForegroundService(this, intent)
        isRecording = false
        stealthTimerJob?.cancel()
        lissajousJob?.cancel()
        showScreen(1)
    }

    // =========================================================================
    // СЛУЖЕБНЫЙ КАМЕРНЫЙ КОД И ГИРОСКОП
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startPreviewCamera() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val st = previewView.surfaceTexture ?: return
        st.setDefaultBufferSize(1920, 1440)
        val surf = Surface(st)

        cm.openCamera("0", object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                previewCamera = camera
                val b = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surf)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, 300_000_000L)
                    set(CaptureRequest.SENSOR_SENSITIVITY, isoVal.coerceAtMost(3000))
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusVal)
                }
                camera.createCaptureSession(listOf(surf), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(sess: CameraCaptureSession) {
                        previewSession = sess
                        sess.setRepeatingRequest(b.build(), null, null)
                    }
                    override fun onConfigureFailed(sess: CameraCaptureSession) {}
                }, null)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, null)
    }

    private fun updatePreviewFocus() {
        previewSession?.let { sess ->
            previewCamera?.let { cam ->
                val st = previewView.surfaceTexture ?: return
                val surf = Surface(st)
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surf)
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.SENSOR_EXPOSURE_TIME, 300_000_000L)
                    set(CaptureRequest.SENSOR_SENSITIVITY, isoVal.coerceAtMost(3000))
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, focusVal)
                }
                sess.setRepeatingRequest(b.build(), null, null)
            }
        }
    }

    private fun stopPreviewCamera() {
        previewSession?.close(); previewSession = null
        previewCamera?.close(); previewCamera = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            pitchAngle = (atan2(ay.toDouble(), sqrt((ax * ax + az * az).toDouble())) * (180.0 / Math.PI)).toFloat()
            rollAngle = (atan2(-ax.toDouble(), az.toDouble()) * (180.0 / Math.PI)).toFloat()
            overlayView.pitch = pitchAngle
            overlayView.roll = rollAngle
            overlayView.invalidate()
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onResume() {
        super.onResume()
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        ContextCompat.registerReceiver(this, telemetryReceiver, IntentFilter("ASTRO_TELEMETRY_UPDATE"), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
        unregisterReceiver(telemetryReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPreviewCamera()
        mainScope.cancel()
        if (testProcessorHandle != 0L) NativeAstroProcessor.releaseProcessor(testProcessorHandle)
    }

    private fun checkPermissions() {
        val p = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.FOREGROUND_SERVICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.POST_NOTIFICATIONS)
        if (p.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, p.toTypedArray(), 100)
        }
    }
}

// =========================================================================
// КАСТОМНЫЙ ОВЕРЛЕЙ: 2 ИНТЕРАКТИВНЫЕ ЛИНИИ, ГИРОСКОП-МИШЕНЬ И СЕТКА
// =========================================================================
class CalibrationOverlayView(context: Context) : View(context) {

    var onLinesChanged: ((Float, Float) -> Unit)? = null
    var pitch = 0.0f
    var roll = 0.0f
    var gridType = 1
    var isZenith = false
    var isLoupe = false

    private var skyLineY = 0f
    private var gndLineY = 0f
    private var draggingLine = 0 // 0=нет, 1=зеленая, 2=красная

    private val skyLinePaint = Paint().apply { color = Color.GREEN; strokeWidth = 5f }
    private val gndLinePaint = Paint().apply { color = Color.RED; strokeWidth = 5f }
    private val bufferPaint = Paint().apply { color = Color.argb(45, 255, 255, 0); style = Paint.Style.FILL }
    private val shieldPaint = Paint().apply { color = Color.argb(90, 220, 0, 0); style = Paint.Style.FILL }
    private val gridPaint = Paint().apply { color = Color.argb(80, 255, 255, 255); strokeWidth = 2f }
    private val textPaint = Paint().apply { color = Color.WHITE; textSize = 28f; isAntiAlias = true }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        skyLineY = h * 0.55f
        gndLineY = h * 0.72f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (isLoupe) return

        val W = width.toFloat()
        val H = height.toFloat()

        // 1. Сетки
        if (gridType == 1) { // 3x3
            canvas.drawLine(W / 3f, 0f, W / 3f, H, gridPaint)
            canvas.drawLine(2 * W / 3f, 0f, 2 * W / 3f, H, gridPaint)
            canvas.drawLine(0f, H / 3f, W, H / 3f, gridPaint)
            canvas.drawLine(0f, 2 * H / 3f, W, 2 * H / 3f, gridPaint)
        } else if (gridType == 2) { // Полярный прицел
            val cx = W / 2f; val cy = H / 2f
            canvas.drawLine(cx - 60f, cy, cx + 60f, cy, gridPaint)
            canvas.drawLine(cx, cy - 60f, cx, cy + 60f, gridPaint)
            canvas.drawCircle(cx, cy, 120f, gridPaint)
            canvas.drawCircle(cx, cy, 260f, gridPaint)
            canvas.drawCircle(cx, cy, 420f, gridPaint)
        }

        // 2. Две линии (если не 90° вверх)
        if (!isZenith) {
            canvas.drawRect(0f, skyLineY, W, gndLineY, bufferPaint)
            canvas.drawRect(0f, gndLineY, W, H, shieldPaint)
            canvas.drawLine(0f, skyLineY, W, skyLineY, skyLinePaint)
            canvas.drawLine(0f, gndLineY, W, gndLineY, gndLinePaint)

            canvas.drawText("🟢 ВЫШЕ: 100% БУСТ ЗВЕЗД", 24f, skyLineY - 10f, skyLinePaint)
            canvas.drawText("🟡 ПЛАВНЫЙ БУФЕР ЗАСВЕТКИ", 24f, (skyLineY + gndLineY) / 2f + 8f, textPaint)
            canvas.drawText("🔴 НИЖЕ: ЩИТ ОТ ФОНАРЯ СОСЕДА", 24f, gndLineY + 34f, gndLinePaint)
        }

        // 3. Умный гироскоп
        if (pitch > 78f || isZenith) {
            // Круговой пузырьковый уровень Зенита (90 градусов)
            val cx = W / 2f; val cy = H * 0.22f
            canvas.drawCircle(cx, cy, 70f, gridPaint)
            canvas.drawCircle(cx, cy, 15f, gridPaint)
            val bx = cx + (roll * 2.5f)
            val by = cy + ((90f - pitch) * 2.5f)
            val pBull = Paint().apply { color = Color.CYAN; style = Paint.Style.FILL }
            canvas.drawCircle(bx, by, 12f, pBull)
            canvas.drawText(String.format("ЗЕНИТ ПРИЦЕЛ: %.1f°", pitch), cx - 110f, cy + 110f, textPaint)
        } else {
            // Линейный уровень горизонта
            val cy = H * 0.14f
            canvas.drawText(String.format("Наклон: %.1f°  |  Горизонт: %.1f°", pitch, roll), 30f, cy, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isZenith || isLoupe) return false
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingLine = when {
                    abs(y - skyLineY) < 50f -> 1
                    abs(y - gndLineY) < 50f -> 2
                    else -> 0
                }
                return draggingLine != 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingLine == 1) {
                    skyLineY = y.coerceIn(100f, gndLineY - 40f)
                    invalidate()
                    onLinesChanged?.invoke(skyLineY / height * 100f, gndLineY / height * 100f)
                } else if (draggingLine == 2) {
                    gndLineY = y.coerceIn(skyLineY + 40f, height.toFloat() - 100f)
                    invalidate()
                    onLinesChanged?.invoke(skyLineY / height * 100f, gndLineY / height * 100f)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingLine = 0
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
