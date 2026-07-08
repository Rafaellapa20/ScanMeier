package ch.greenonline.ernstmeier

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import ch.greenonline.ernstmeier.databinding.ActivityScannerBinding
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import kotlin.math.max
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScannerActivity : AppCompatActivity() {
    companion object {
        var globalIsAutoScanMode = false
    }
    
    private lateinit var binding: ActivityScannerBinding
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null
    private var isTorchOn = false
    private var isAutoScanMode = false
    private var isFinished = false

    private var lastAutoScanBarcode: String? = null
    private var lastAutoScanTime: Long = 0
    
    private var isSoundEnabled = true

    private fun processBarcode(rawValue: String) {
        if (isFinished) return
        isFinished = true
        if (isSoundEnabled) {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)

            // Haptic Feedback (Vibração)
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }

        val intent = Intent()
        intent.putExtra("SCANNED_CODE", rawValue)
        setResult(RESULT_OK, intent)
        finish()
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Permissão da câmera é necessária", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val prefs = getSharedPreferences("UI_PREFS", Context.MODE_PRIVATE)
        isSoundEnabled = prefs.getBoolean("IS_SOUND_ENABLED", true)
        updateSoundIcon()

        isAutoScanMode = globalIsAutoScanMode

        updateModeUI()
        val scaleGestureDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val currentZoomRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
                    val delta = detector.scaleFactor
                    camera?.cameraControl?.setZoomRatio(currentZoomRatio * delta)
                    return true
                }
            })

        binding.viewFinder.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            
            if (event.action == MotionEvent.ACTION_DOWN) {
                val tappedBarcode = binding.barcodeOverlay.getBarcodeAtPosition(event.x, event.y)
                if (tappedBarcode != null && tappedBarcode.rawValue != null) {
                    processBarcode(tappedBarcode.rawValue!!)
                } else {
                    // Tap-to-focus if touched outside a barcode
                    val factory = binding.viewFinder.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                    camera?.cameraControl?.cancelFocusAndMetering()
                    camera?.cameraControl?.startFocusAndMetering(action)
                    binding.barcodeOverlay.showFocusAnimation(event.x, event.y)
                }
            }
            true
        }

        cameraExecutor = Executors.newSingleThreadExecutor()

        updateModeUI()
        binding.btnMode.setOnClickListener {
            toggleMode()
        }

        // makeDraggable trata cliques e arrastos internamente via lambda
        makeDraggable(binding.btnClose) { finish() }
        makeDraggable(binding.btnTorch) {
            isTorchOn = !isTorchOn
            camera?.cameraControl?.enableTorch(isTorchOn)
        }
        makeDraggable(binding.btnSound) {
            isSoundEnabled = !isSoundEnabled
            getSharedPreferences("UI_PREFS", Context.MODE_PRIVATE).edit().putBoolean("IS_SOUND_ENABLED", isSoundEnabled).apply()
            updateSoundIcon()
        }

        binding.btnZoom1.setOnClickListener { setZoom(1f) }
        binding.btnZoom2.setOnClickListener { setZoom(2f) }
        binding.btnZoom4.setOnClickListener { setZoom(4f) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleMode() {
        isAutoScanMode = !isAutoScanMode
        globalIsAutoScanMode = isAutoScanMode
        binding.barcodeOverlay.setAutoScanMode(isAutoScanMode)
        updateModeUI()
        
        if (!isAutoScanMode) {
            setZoom(2f)
        } else {
            setZoom(1f)
        }
    }

    private fun updateModeUI() {
        binding.btnMode.text = if (isAutoScanMode) "Modus: Automatisch" else "Modus: Manuell"
        binding.tvInstructions.text = if (isAutoScanMode) "Auf den Code richten für automatischen Scan" else "Code antippen zum Scannen"
    }

    private fun updateSoundIcon() {
        if (isSoundEnabled) {
            binding.btnSound.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        } else {
            binding.btnSound.setImageResource(android.R.drawable.ic_lock_silent_mode)
        }
    }

    private fun setZoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
        val selectedColor = android.graphics.Color.parseColor("#4CAF50") // Verde da marca
        val defaultColor = android.graphics.Color.parseColor("#44000000") // Preto semi-transparente
        
        binding.btnZoom1.setBackgroundColor(if (ratio == 1f) selectedColor else defaultColor)
        binding.btnZoom2.setBackgroundColor(if (ratio == 2f) selectedColor else defaultColor)
        binding.btnZoom4.setBackgroundColor(if (ratio == 4f) selectedColor else defaultColor)
    }

    /**
     * Torna um view arrastável no ecrã.
     * Usa translationX/Y acumulada para mover o botão.
     * Se o dedo se mover menos de CLICK_THRESHOLD, chama onClickAction().
     */
    private fun makeDraggable(view: View, onClickAction: () -> Unit) {
        val CLICK_THRESHOLD = 12f
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f

        val prefs = getSharedPreferences("UI_PREFS", Context.MODE_PRIVATE)
        val keyX = "SCAN_BTN_${view.id}_TX"
        val keyY = "SCAN_BTN_${view.id}_TY"

        if (prefs.contains(keyX) && prefs.contains(keyY)) {
            view.translationX = prefs.getFloat(keyX, 0f)
            view.translationY = prefs.getFloat(keyY, 0f)
        }

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    v.animate().cancel()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastRawX
                    val dy = event.rawY - lastRawY
                    v.translationX += dx
                    v.translationY += dy
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val totalDx = Math.abs(event.rawX - downRawX)
                    val totalDy = Math.abs(event.rawY - downRawY)
                    // Se o movimento foi pequeno, é um clique
                    if (totalDx < CLICK_THRESHOLD && totalDy < CLICK_THRESHOLD) {
                        onClickAction()
                    }
                    prefs.edit().putFloat(keyX, v.translationX).putFloat(keyY, v.translationY).apply()
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build()

            val imageAnalyzer = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor, BarcodeAnalyzer { barcodes, imageWidth, imageHeight ->
                        runOnUiThread {
                            if (isAutoScanMode && barcodes.isNotEmpty() && !isFinished) {
                                val barcode = barcodes.firstOrNull { b -> 
                                    b.rawValue != null && (b.format == Barcode.FORMAT_EAN_13 || b.format == Barcode.FORMAT_EAN_8 || b.format == Barcode.FORMAT_QR_CODE)
                                }
                                if (barcode != null) {
                                    val now = System.currentTimeMillis()
                                    if (barcode.rawValue == lastAutoScanBarcode) {
                                        if (now - lastAutoScanTime >= 200) {
                                            processBarcode(barcode.rawValue!!)
                                            return@runOnUiThread
                                        }
                                    } else {
                                        lastAutoScanBarcode = barcode.rawValue
                                        lastAutoScanTime = now
                                    }
                                } else {
                                    lastAutoScanBarcode = null
                                }
                            } else {
                                lastAutoScanBarcode = null
                            }
                            
                            binding.barcodeOverlay.setAutoScanMode(isAutoScanMode)
                            binding.barcodeOverlay.setBarcodes(barcodes, imageWidth, imageHeight)
                        }
                    })
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)

                // Para acelerar a leitura e foco, focamos no centro ao ligar e fazemos AE/AWB
                binding.viewFinder.post {
                    val factory = binding.viewFinder.meteringPointFactory
                    val point = factory.createPoint(binding.viewFinder.width / 2f, binding.viewFinder.height / 2f)
                    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB)
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                    
                    if (!isAutoScanMode) {
                        setZoom(2f)
                    } else {
                        setZoom(1f)
                    }
                }

                // Deixamos a câmara usar o Autofocus Contínuo (CAF) nativo que é muito mais rápido no arranque
                // Foco manual só é acionado se o utilizador tocar no ecrã

            } catch (exc: Exception) {
                Log.e("ScannerActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

object ScannerInstance {
    val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E
        )
        .build()
    val client = BarcodeScanning.getClient(options)
}

class BarcodeAnalyzer(private val onBarcodesDetected: (List<Barcode>, Int, Int) -> Unit) : ImageAnalysis.Analyzer {
    private val scanner = ScannerInstance.client

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            scanner.process(image)
                .addOnSuccessListener { barcodes ->
                    val isPortrait = imageProxy.imageInfo.rotationDegrees == 90 || imageProxy.imageInfo.rotationDegrees == 270
                    val imageWidth = if (isPortrait) imageProxy.height else imageProxy.width
                    val imageHeight = if (isPortrait) imageProxy.width else imageProxy.height
                    onBarcodesDetected(barcodes, imageWidth, imageHeight)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        } else {
            imageProxy.close()
        }
    }
}

class BarcodeOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 4f // Reduzido de 8f para 4f para ser mais fino e preciso
    }
    
    private val bgPaint = Paint().apply {
        color = Color.parseColor("#4400FF00")
        style = Paint.Style.FILL
    }

    private var barcodes: List<Barcode> = emptyList()
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    
    private var isAutoScanMode: Boolean = false
    private var laserYOffset = 0f
    private var laserDirection = 1f
    private var focusX = -1f
    private var focusY = -1f
    private var focusAlpha = 0

    private val linePaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 6f
        setShadowLayer(10f, 0f, 0f, Color.RED)
    }

    private val focusPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    private val darkOverlayPaint = Paint().apply {
        color = Color.parseColor("#99000000") // Escuro semi-transparente
        style = Paint.Style.FILL
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // Necessário para setShadowLayer brilhar
    }

    fun setAutoScanMode(autoMode: Boolean) {
        this.isAutoScanMode = autoMode
        invalidate()
    }

    fun showFocusAnimation(x: Float, y: Float) {
        focusX = x
        focusY = y
        focusAlpha = 255
        val animator = android.animation.ValueAnimator.ofInt(255, 0)
        animator.duration = 600
        animator.addUpdateListener {
            focusAlpha = it.animatedValue as Int
            invalidate()
        }
        animator.start()
    }

    fun setBarcodes(barcodes: List<Barcode>, imageWidth: Int, imageHeight: Int) {
        this.barcodes = barcodes
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        invalidate()
    }

    fun getBarcodeAtPosition(x: Float, y: Float): Barcode? {
        if (barcodes.isEmpty() || imageWidth == 0 || imageHeight == 0) return null

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val scale = max(scaleX, scaleY)
        val offsetX = (width - imageWidth * scale) / 2
        val offsetY = (height - imageHeight * scale) / 2

        val imageX = (x - offsetX) / scale
        val imageY = (y - offsetY) / scale

        return barcodes.firstOrNull { barcode ->
            barcode.boundingBox?.let { rect ->
                // Encolher a área de toque em 15% de cada lado para ser super preciso
                val shrinkX = rect.width() * 0.15f
                val shrinkY = rect.height() * 0.15f
                val tightLeft = rect.left + shrinkX
                val tightRight = rect.right - shrinkX
                val tightTop = rect.top + shrinkY
                val tightBottom = rect.bottom - shrinkY

                imageX >= tightLeft && imageX <= tightRight &&
                imageY >= tightTop && imageY <= tightBottom
            } == true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (isAutoScanMode) {
            val centerY = height / 2f
            // Desenhar o risco vermelho no centro
            canvas.drawLine(0f, centerY, width.toFloat(), centerY, linePaint)
        }

        if (focusAlpha > 0) {
            focusPaint.alpha = focusAlpha
            val size = 100f
            canvas.drawRect(focusX - size, focusY - size, focusX + size, focusY + size, focusPaint)
            
            // Desenhar os cantos mais grossos para dar aquele ar de câmara
            val cornerLength = 30f
            canvas.drawLine(focusX - size, focusY - size, focusX - size + cornerLength, focusY - size, focusPaint)
            canvas.drawLine(focusX - size, focusY - size, focusX - size, focusY - size + cornerLength, focusPaint)
            
            canvas.drawLine(focusX + size, focusY - size, focusX + size - cornerLength, focusY - size, focusPaint)
            canvas.drawLine(focusX + size, focusY - size, focusX + size, focusY - size + cornerLength, focusPaint)

            canvas.drawLine(focusX - size, focusY + size, focusX - size + cornerLength, focusY + size, focusPaint)
            canvas.drawLine(focusX - size, focusY + size, focusX - size, focusY + size - cornerLength, focusPaint)

            canvas.drawLine(focusX + size, focusY + size, focusX + size - cornerLength, focusY + size, focusPaint)
            canvas.drawLine(focusX + size, focusY + size, focusX + size, focusY + size - cornerLength, focusPaint)
        }

        if (barcodes.isEmpty() || imageWidth == 0 || imageHeight == 0) return

        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight
        val scale = max(scaleX, scaleY)
        val offsetX = (width - imageWidth * scale) / 2
        val offsetY = (height - imageHeight * scale) / 2

        for (barcode in barcodes) {
            barcode.boundingBox?.let { rect ->
                // Encolher a caixa desenhada em 15% para abraçar apenas o centro do código
                val shrinkX = rect.width() * 0.15f
                val shrinkY = rect.height() * 0.15f
                val tightLeft = rect.left + shrinkX
                val tightRight = rect.right - shrinkX
                val tightTop = rect.top + shrinkY
                val tightBottom = rect.bottom - shrinkY

                val left = tightLeft * scale + offsetX
                val top = tightTop * scale + offsetY
                val right = tightRight * scale + offsetX
                val bottom = tightBottom * scale + offsetY
                
                canvas.drawRect(left, top, right, bottom, bgPaint)
                canvas.drawRect(left, top, right, bottom, paint)
            }
        }
    }
}
