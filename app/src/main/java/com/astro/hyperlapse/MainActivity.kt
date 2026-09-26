package com.astro.hyperlapse

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.*
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var rootFlipper: ViewFlipper
    private lateinit var sensorManager: SensorManager

    // Экран 1: Датчики
    private lateinit var tvSensorsInfo: TextView
    private var gyroX = 0f; private var gyroY = 0f; private var gyroZ = 0f
    private var pitchDeg = 0f; private var rollDeg = 0f
    private var batteryTemp = 0f; private var batteryPct = 0

    // Экран 2: Видоискатель + Лупа 10x + Пробное фото
    private lateinit var textureView: TextureView
    private lateinit var overlayView: AstroOverlayView
    private var previewCamera: CameraDevice? = null
    private var previewSession: CameraCaptureSession? = null
    private var testPhotoReader: ImageReader? = null

    private var manualFocusDist = 0.0f
    private var isLoupe10x = false
    private var maskHorizonPercent = 50
    private var starGainValue = 35f
    private var blackCutValue = 22 // Порог отсечения темнового шума матрицы

    // Диалог живой докрутки пробного фото
    private var rawTestBitmap: Bitmap? = null
    private lateinit var testPreviewContainer: LinearLayout
    private lateinit var ivTestResult: ImageView

    // Экран 3: Настройки марафона
    private var isStepMode = false
    private var settingExposureSec = 15
    private var settingIso = 2000
    private var settingWindowSize = 16
    private var settingDlss = 4
    private var settingBitrate = 100
    private var settingScreenOffMin = 10
    private var settingLissajousPx = 6

    // Экран 4: Ночной полет (Защита AMOLED)
    private lateinit var tvAmoledStatus: TextView
    private var screenStartTimeMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        buildFullInterface()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun buildFullInterface() {
        rootFlipper = ViewFlipper(this).apply { setBackgroundColor(Color.parseColor("#080B12")) }

        rootFlipper.addView(buildScreen1Diagnostics())
        rootFlipper.addView(buildScreen2Viewfinder())
        rootFlipper.addView(buildScreen3Settings())
        rootFlipper.addView(buildScreen4Recording())

        setContentView(rootFlipper)
        startUiTelemetryLoop()
    }

    // ================= ЭКРАН 1: ДИАГНОСТИКА =================
    private fun buildScreen1Diagnostics(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }
        val title = TextView(this).apply {
            text = "🛰 ПРЕДПОЛЕТНАЯ ДИАГНОСТИКА S22 ULTRA"
            textSize = 20f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, Typeface.BOLD)
        }
        tvSensorsInfo = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 35, 0, 35)
        }
        val btnNext = Button(this).apply {
            text = "ПЕРЕЙТИ К НАВЕДЕНИЮ И ФОКУСУ ➔"
            textSize = 16f
            setBackgroundColor(Color.parseColor("#0284C7"))
            setTextColor(Color.WHITE)
            setPadding(20, 35, 20, 35)
            setOnClickListener {
                rootFlipper.displayedChild = 1
                startPreviewCamera()
            }
        }
        layout.addView(title)
        layout.addView(tvSensorsInfo)
        layout.addView(btnNext)
        return ScrollView(this).apply { addView(layout) }
    }

    // ================= ЭКРАН 2: ВИДОИСКАТЕЛЬ + ПРОБНОЕ ФОТО + КНОПКА ПРОПУСТИТЬ =================
    private fun buildScreen2Viewfinder(): View {
        val mainBox = FrameLayout(this)

        val camContainer = FrameLayout(this)
        textureView = TextureView(this)
        overlayView = AstroOverlayView(this)
        camContainer.addView(textureView)
        camContainer.addView(overlayView)

        val controlsPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC080B12"))
            setPadding(30, 20, 30, 30)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
        }

        val btnLoupe = Button(this).apply {
            text = "🔍 ЛУПА 10x ДЛЯ ФОКУСА: ВЫКЛ"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isLoupe10x = !isLoupe10x
                text = if (isLoupe10x) "🔍 ЛУПА 10x ДЛЯ ФОКУСА: ВКЛ (НАВЕДИ НА ЗВЕЗДУ)" else "🔍 ЛУПА 10x ДЛЯ ФОКУСА: ВЫКЛ"
                setBackgroundColor(if (isLoupe10x) Color.parseColor("#0369A1") else Color.parseColor("#334155"))
                updatePreviewSession()
            }
        }

        val tvFocus = TextView(this).apply {
            text = "Ручной фокус (Бесконечность = 0.00): 0.00"
            setTextColor(Color.parseColor("#FACC15"))
            setPadding(0, 12, 0, 4)
        }
        val sbFocus = SeekBar(this).apply {
            max = 200
            progress = 0
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    manualFocusDist = p / 100.0f
                    tvFocus.text = String.format(Locale.US, "Ручной фокус (Звезды = 0.00..0.15): %.2f", manualFocusDist)
                    updatePreviewSession()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvMask = TextView(this).apply {
            text = "Мягкое приглушение фонаря снизу: $maskHorizonPercent%"
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 10, 0, 4)
        }
        val sbMask = SeekBar(this).apply {
            max = 100
            progress = maskHorizonPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    maskHorizonPercent = p
                    tvMask.text = "Мягкое приглушение фонаря снизу: $p%"
                    overlayView.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        // Ряд из двух кнопок: ПРОБНОЕ ФОТО и ПРОПУСТИТЬ
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }

        val btnTestShot = Button(this).apply {
            text = "✨ ПРОБНОЕ ФОТО"
            setBackgroundColor(Color.parseColor("#7C3AED"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 }
            setOnClickListener { captureTestAstroPhoto() }
        }

        val btnSkipToSettings = Button(this).apply {
            text = "⏭ ПРОПУСТИТЬ ➔"
            setBackgroundColor(Color.parseColor("#B91C1C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 10 }
            setOnClickListener {
                closePreviewCamera()
                rootFlipper.displayedChild = 2
            }
        }

        actionRow.addView(btnTestShot)
        actionRow.addView(btnSkipToSettings)

        controlsPanel.addView(btnLoupe)
        controlsPanel.addView(tvFocus)
        controlsPanel.addView(sbFocus)
        controlsPanel.addView(tvMask)
        controlsPanel.addView(sbMask)
        controlsPanel.addView(actionRow)

        // Панель живой проявки пробного фото (поверх экрана)
        testPreviewContainer = buildLiveTestEditorOverlay()
        testPreviewContainer.visibility = View.GONE

        mainBox.addView(camContainer)
        mainBox.addView(controlsPanel)
        mainBox.addView(testPreviewContainer)
        return mainBox
    }

    private fun buildLiveTestEditorOverlay(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5080B12"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setPadding(25, 40, 25, 30)
        }

        ivTestResult = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val lblTitle = TextView(this).apply {
            text = "🎨 ЖИВАЯ ДОКРУТКА ПРОБНОГО ФОТО (Честная темнота)"
            setTextColor(Color.parseColor("#FACC15"))
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 10, 0, 6)
        }

        val tvGain = TextView(this).apply {
            text = "Яркость звезд (Gain): ${starGainValue.toInt()}"
            setTextColor(Color.parseColor("#F87171"))
        }
        val sbGain = SeekBar(this).apply {
            max = 100
            progress = starGainValue.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    starGainValue = p.toFloat()
                    tvGain.text = "Яркость звезд (Gain): $p"
                    refreshLiveTestBitmap()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvBlack = TextView(this).apply {
            text = "Отсечение шума матрицы (Чернота неба): $blackCutValue"
            setTextColor(Color.parseColor("#A78BFA"))
        }
        val sbBlack = SeekBar(this).apply {
            max = 80
            progress = blackCutValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    blackCutValue = p
                    tvBlack.text = "Отсечение шума матрицы (Чернота неба): $p"
                    refreshLiveTestBitmap()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvGrad = TextView(this).apply {
            text = "Плавное подавление фонаря соседа: $maskHorizonPercent%"
            setTextColor(Color.parseColor("#38BDF8"))
        }
        val sbGrad = SeekBar(this).apply {
            max = 100
            progress = maskHorizonPercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    maskHorizonPercent = p
                    tvGrad.text = "Плавное подавление фонаря соседа: $p%"
                    refreshLiveTestBitmap()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        val btnRetake = Button(this).apply {
            text = "🔄 ПЕРЕСНЯТЬ"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 10 }
            setOnClickListener { testPreviewContainer.visibility = View.GONE }
        }
        val btnAccept = Button(this).apply {
            text = "🔥 КАДР ИДЕАЛЕН! К НАСТРОЙКАМ ➔"
            setBackgroundColor(Color.parseColor("#B91C1C"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
            setOnClickListener {
                testPreviewContainer.visibility = View.GONE
                closePreviewCamera()
                rootFlipper.displayedChild = 2
            }
        }
        btnRow.addView(btnRetake)
        btnRow.addView(btnAccept)

        box.addView(ivTestResult)
        box.addView(lblTitle)
        box.addView(tvGain)
        box.addView(sbGain)
        box.addView(tvBlack)
        box.addView(sbBlack)
        box.addView(tvGrad)
        box.addView(sbGrad)
        box.addView(btnRow)
        return box
    }

    // Честная математика проявки пробного фото: на коврике будет ЧЕРНЫЙ экран, а градиент фонаря — плавный!
    private fun refreshLiveTestBitmap() {
        val src = rawTestBitmap ?: return
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        // Считаем средний уровень темнового пола
        var sumLum = 0L
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            sumLum += minOf(r, g, b)
        }
        val avgFloor = (sumLum / pixels.size.coerceAtLeast(1)).toFloat() * 0.85f
        val totalCut = (avgFloor + blackCutValue).coerceIn(0f, 225f)
        val gainMult = 0.5f + (starGainValue / 100f) * 3.5f
        val maskNorm = (maskHorizonPercent / 100f).coerceIn(0f, 1f)

        val gradStartY = h * 0.22f
        val gradEndY = h.toFloat()

        for (y in 0 until h) {
            // Плавный Smoothstep градиент на 78% высоты кадра (никаких резких черно-белых полос!)
            val t = ((y - gradStartY) / (gradEndY - gradStartY)).coerceIn(0f, 1f)
            val smoothT = t * t * (3f - 2f * t)
            val rowAtten = 1.0f - (maskNorm * 0.92f * smoothT)

            val rowOffset = y * w
            for (x in 0 until w) {
                val p = pixels[rowOffset + x]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()

                val cr = max(0f, r - totalCut) / (255f - totalCut + 1f)
                val cg = max(0f, g - totalCut) / (255f - totalCut + 1f)
                val cb = max(0f, b - totalCut) / (255f - totalCut + 1f)

                val nr = (cr.pow(0.88f) * 255f * gainMult * rowAtten).toInt().coerceIn(0, 255)
                val ng = (cg.pow(0.88f) * 255f * gainMult * rowAtten).toInt().coerceIn(0, 255)
                val nb = (cb.pow(0.88f) * 255f * gainMult * rowAtten).toInt().coerceIn(0, 255)

                pixels[rowOffset + x] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        ivTestResult.setImageBitmap(out)
    }

    // ================= ЭКРАН 3: НАСТРОЙКИ МАРАФОНА СЪЕМКИ =================
    private fun buildScreen3Settings(): View {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(35, 50, 35, 40)
        }
        val title = TextView(this).apply {
            text = "⚙ НАСТРОЙКИ МАРАФОНА СЪЕМКИ"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 20)
        }

        val btnMode = Button(this).apply {
            text = "🎬 РЕЖИМ: СКОЛЬЗЯЩИЙ (ДЛИННОЕ ВИДЕО 3–5 МИНУТ)"
            setBackgroundColor(Color.parseColor("#4C1D95"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                isStepMode = !isStepMode
                text = if (!isStepMode) "🎬 РЕЖИМ: СКОЛЬЗЯЩИЙ (ДЛИННОЕ ВИДЕО 3–5 МИНУТ)"
                else "⚡ РЕЖИМ: ШАГОВЫЙ (КОРОТКОЕ ВИДЕО 30 СЕК)"
                setBackgroundColor(if (!isStepMode) Color.parseColor("#4C1D95") else Color.parseColor("#065F46"))
            }
        }

        val tvExp = TextView(this).apply {
            text = "Выдержка одного подкадра: $settingExposureSec сек"
            setTextColor(Color.parseColor("#F87171"))
            setPadding(0, 20, 0, 5)
        }
        val sbExp = SeekBar(this).apply {
            max = 28; progress = 13
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    settingExposureSec = p + 2
                    tvExp.text = "Выдержка одного подкадра: $settingExposureSec сек"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvIso = TextView(this).apply {
            text = "ISO (Сенсор 1x, лимит 3000): ISO $settingIso"
            setTextColor(Color.parseColor("#F87171"))
            setPadding(0, 16, 0, 5)
        }
        val sbIso = SeekBar(this).apply {
            max = 26; progress = 16
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    settingIso = 400 + p * 100
                    tvIso.text = "ISO (Сенсор 1x, лимит 3000): ISO $settingIso"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvWin = TextView(this).apply {
            text = "Длина выдержки Мастер-кадра: $settingWindowSize подкадров"
            setTextColor(Color.parseColor("#F87171"))
            setPadding(0, 16, 0, 5)
        }
        val sbWin = SeekBar(this).apply {
            max = 28; progress = 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    settingWindowSize = p + 4
                    val totalSec = settingWindowSize * settingExposureSec
                    tvWin.text = "Длина выдержки Мастер-кадра: $settingWindowSize подкадров (~${totalSec / 60} мин ${totalSec % 60} сек света)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvDlss = TextView(this).apply {
            text = "Астро-DLSS (Генератор плавности): ${settingDlss}x (DLSS 60 FPS)"
            setTextColor(Color.parseColor("#F87171"))
            setPadding(0, 16, 0, 5)
        }
        val sbDlss = SeekBar(this).apply {
            max = 7; progress = 3
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    settingDlss = p + 1
                    tvDlss.text = "Астро-DLSS (Генератор плавности): ${settingDlss}x"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val tvBitrate = TextView(this).apply {
            text = "Битрейт 4K HEVC видео: $settingBitrate Мбит/с (Без квадратов)"
            setTextColor(Color.parseColor("#F87171"))
            setPadding(0, 16, 0, 20)
        }
        val sbBitrate = SeekBar(this).apply {
            max = 100; progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    settingBitrate = 50 + p
                    tvBitrate.text = "Битрейт 4K HEVC видео: $settingBitrate Мбит/с (Без квадратов)"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }

        val navRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 30, 0, 0)
        }
        val btnBack = Button(this).apply {
            text = "⬅ НАЗАД"
            setBackgroundColor(Color.parseColor("#334155"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 12 }
            setOnClickListener {
                rootFlipper.displayedChild = 1
                startPreviewCamera()
            }
        }
        val btnStartMarathon = Button(this).apply {
            text = "🌙 ЗАПУСТИТЬ НОЧНОЙ ГИПЕРЛАПС"
            setBackgroundColor(Color.parseColor("#991B1B"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            setOnClickListener { launchAstroMarathon() }
        }
        navRow.addView(btnBack)
        navRow.addView(btnStartMarathon)

        layout.addView(title)
        layout.addView(btnMode)
        layout.addView(tvExp); layout.addView(sbExp)
        layout.addView(tvIso); layout.addView(sbIso)
        layout.addView(tvWin); layout.addView(sbWin)
        layout.addView(tvDlss); layout.addView(sbDlss)
        layout.addView(tvBitrate); layout.addView(sbBitrate)
        layout.addView(navRow)

        return ScrollView(this).apply { addView(layout) }
    }

    // ================= ЭКРАН 4: ЗАПИСЬ + ЧЕРНЫЙ ЭКРАН AMOLED =================
    private fun buildScreen4Recording(): View {
        val box = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        tvAmoledStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#7F1D1D"))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }

        val btnStop = Button(this).apply {
            text = "⏹ ОСТАНОВИТЬ И СОХРАНИТЬ 4K ВИДЕО"
            setBackgroundColor(Color.parseColor("#450A0A"))
            setTextColor(Color.parseColor("#FCA5A5"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { setMargins(40, 40, 40, 50) }
            setOnClickListener {
                // Мгновенный отклик без зависаний!
                isEnabled = false
                text = "⏳ ЗАКРЫТИЕ ЗАТВОРА И СОХРАНЕНИЕ..."
                val stopIntent = Intent(this@MainActivity, AstroCameraService::class.java).apply {
                    action = "STOP_ASTRO"
                }
                startService(stopIntent)

                uiHandler.postDelayed({
                    isEnabled = true
                    text = "⏹ ОСТАНОВИТЬ И СОХРАНИТЬ 4K ВИДЕО"
                    Toast.makeText(this@MainActivity, AstroCameraService.lastStatusText, Toast.LENGTH_LONG).show()
                    rootFlipper.displayedChild = 2
                }, 1200)
            }
        }

        box.addView(tvAmoledStatus)
        box.addView(btnStop)
        return box
    }

    private fun launchAstroMarathon() {
        closePreviewCamera()
        screenStartTimeMs = System.currentTimeMillis()
        rootFlipper.displayedChild = 3

        val intent = Intent(this, AstroCameraService::class.java).apply {
            putExtra("EXPOSURE", settingExposureSec)
            putExtra("ISO", settingIso)
            putExtra("WINDOW", settingWindowSize)
            putExtra("DLSS", settingDlss)
            putExtra("BITRATE", settingBitrate)
            putExtra("MODE", if (isStepMode) 1 else 0)
            putExtra("FOCUS", manualFocusDist)
            putExtra("MASK", maskHorizonPercent)
            putExtra("GAIN", starGainValue)
            putExtra("BLACK_CUT", blackCutValue)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    // ================= КАМЕРА ВИДОИСКАТЕЛЯ И ПРОБНОЕ ФОТО =================
    @SuppressWarnings("MissingPermission")
    private fun startPreviewCamera() {
        if (!textureView.isAvailable) {
            textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { startPreviewCamera() }
                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = true
                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
            }
            return
        }
        closePreviewCamera()
        val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = mgr.cameraIdList.firstOrNull {
            mgr.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: "0"

        testPhotoReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buf = img.planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                img.close()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) {
                    rawTestBitmap = bmp
                    runOnUiThread {
                        testPreviewContainer.visibility = View.VISIBLE
                        refreshLiveTestBitmap()
                    }
                }
            }, uiHandler)
        }

        try {
            mgr.openCamera(camId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    previewCamera = camera
                    updatePreviewSession()
                }
                override fun onDisconnected(camera: CameraDevice) {}
                override fun onError(camera: CameraDevice, error: Int) {}
            }, uiHandler)
        } catch (_: Exception) {}
    }

    private fun updatePreviewSession() {
        val cam = previewCamera ?: return
        val st = textureView.surfaceTexture ?: return
        st.setDefaultBufferSize(1920, 1080)
        val previewSurface = Surface(st)
        val jpegSurface = testPhotoReader?.surface ?: return

        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface)
                // Фиксируем ручную экспозицию в видоискателе, чтобы камера не высветляла темноту в белый шум!
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 250_000_000L) // 0.25 сек для отзывчивого видоискателя
                set(CaptureRequest.SENSOR_SENSITIVITY, 1600)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDist)

                if (isLoupe10x) {
                    val mgr = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val chars = mgr.getCameraCharacteristics(cam.id)
                    val rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                    if (rect != null) {
                        val cw = rect.width() / 8
                        val ch = rect.height() / 8
                        val cx = rect.centerX()
                        val cy = rect.centerY()
                        set(CaptureRequest.SCALER_CROP_REGION, Rect(cx - cw / 2, cy - ch / 2, cx + cw / 2, cy + ch / 2))
                    }
                }
            }
            cam.createCaptureSession(listOf(previewSurface, jpegSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    previewSession = session
                    try { session.setRepeatingRequest(req.build(), null, uiHandler) } catch (_: Exception) {}
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {}
            }, uiHandler)
        } catch (_: Exception) {}
    }

    private fun captureTestAstroPhoto() {
        val cam = previewCamera ?: return
        val sess = previewSession ?: return
        val jpegSurface = testPhotoReader?.surface ?: return
        Toast.makeText(this, "Съемка пробного кадра (1.5 сек)...", Toast.LENGTH_SHORT).show()
        try {
            val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, 1_500_000_000L) // 1.5 сек честной выдержки без авто-высветления!
                set(CaptureRequest.SENSOR_SENSITIVITY, 1600)
                set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDist)
            }
            sess.capture(req.build(), null, uiHandler)
        } catch (_: Exception) {}
    }

    private fun closePreviewCamera() {
        try { previewSession?.close() } catch (_: Exception) {}
        previewSession = null
        try { previewCamera?.close() } catch (_: Exception) {}
        previewCamera = null
        try { testPhotoReader?.close() } catch (_: Exception) {}
        testPhotoReader = null
    }

    // ================= ТЕЛЕМЕТРИЯ И ЗАЩИТА AMOLED =================
    private fun startUiTelemetryLoop() {
        val runnable = object : Runnable {
            override fun run() {
                updateDiagnosticsText()
                updateAmoledSaver()
                uiHandler.postDelayed(this, 500)
            }
        }
        uiHandler.post(runnable)
    }

    private fun updateDiagnosticsText() {
        val bm = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        batteryTemp = (bm?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250) ?: 250) / 10f
        batteryPct = bm?.getIntExtra(BatteryManager.EXTRA_LEVEL, 50) ?: 50

        val shake = sqrt(gyroX * gyroX + gyroY * gyroY + gyroZ * gyroZ)
        val tripodOk = if (shake < 0.03f) "✅ ИДЕАЛЬНО (Штатив монолитен)" else "⚠️ ДРОЖАНИЕ (${String.format(Locale.US, "%.3f", shake)} рад/с)"

        tvSensorsInfo.text = """
            🔋 Заряд батареи: $batteryPct%
            🌡 Температура АКБ: ${batteryTemp}°C ${if (batteryTemp < 39f) "(Холодная — норма)" else "(Нагрев!)"}
            
            📐 Наклон к небу (Pitch): ${String.format(Locale.US, "%.1f°", pitchDeg)}
            ⚖️ Горизонт (Roll): ${String.format(Locale.US, "%.1f°", rollDeg)}
            
            🎯 Стабильность корпуса:
            $tripodOk
            
            💾 Формат записи: 4K UHD (3840x2160) HEVC 100+ Мбит/с
            🌑 Темновой фильтр: АКТИВЕН (Честный черный цвет в темноте)
        """.trimIndent()
    }

    private fun updateAmoledSaver() {
        if (rootFlipper.displayedChild != 3) return
        val t = System.currentTimeMillis() / 1000.0
        val dx = (sin(t * 1.3) * settingLissajousPx * 3).toFloat()
        val dy = (cos(t * 1.7) * settingLissajousPx * 3).toFloat()
        tvAmoledStatus.translationX = dx
        tvAmoledStatus.translationY = dy

        tvAmoledStatus.text = """
            🌙 ASTRO HYPERLAPSE ULTRA (ЗАПИСЬ)
            ${AstroCameraService.lastStatusText}
            Подкадров: ${AstroCameraService.subframesCaptured} | Мастер-кадров: ${AstroCameraService.masterFramesRendered}
            Температура: ${batteryTemp}°C | Заряд: $batteryPct%
        """.trimIndent()
    }

    override fun onResume() {
        super.onResume()
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent?) {
        if (e == null) return
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            gyroX = e.values[0]; gyroY = e.values[1]; gyroZ = e.values[2]
        } else if (e.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
            pitchDeg = Math.toDegrees(atan2(ay.toDouble(), az.toDouble())).toFloat()
            rollDeg = Math.toDegrees(atan2(ax.toDouble(), sqrt((ay * ay + az * az).toDouble()))).toFloat()
            overlayView.invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Визуальная сетка и мягкая зона градиента фонаря в видоискателе
    inner class AstroOverlayView(context: Context) : View(context) {
        private val paintLine = Paint().apply {
            color = Color.parseColor("#8038BDF8")
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        private val paintHorizon = Paint().apply {
            color = Color.parseColor("#AA22C55E")
            strokeWidth = 4f
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            // Горизонт
            val cy = h / 2f
            canvas.save()
            canvas.rotate(-rollDeg, w / 2f, cy)
            canvas.drawLine(w * 0.15f, cy, w * 0.85f, cy, paintHorizon)
            canvas.restore()

            // Линия начала плавного градиента фонаря
            val maskY = h * (1f - (maskHorizonPercent / 100f) * 0.75f)
            canvas.drawLine(0f, maskY, w, maskY, paintLine)
        }
    }
}
