package ch.greenonline.ernstmeier

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import ch.greenonline.ernstmeier.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isScanning = false
    private var isKeyboardMode = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var backPressedTime: Long = 0

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null

    private val fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        var results: Array<Uri>? = null
        if (result.resultCode == RESULT_OK) {
            if (result.data?.data != null) {
                results = arrayOf(result.data?.data!!)
            } else if (cameraPhotoUri != null) {
                results = arrayOf(cameraPhotoUri!!)
            }
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
        cameraPhotoUri = null
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            // Permissão concedida na inicialização, ou via ImageChooser.
        } else {
            Snackbar.make(binding.webview, getString(R.string.camera_permission_required), Snackbar.LENGTH_LONG).show()
        }
    }

    private fun checkAndRequestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun makeDraggable(view: View, keyX: String, keyY: String, onClickAction: () -> Unit) {
        var dX = 0f
        var dY = 0f
        var startX = 0f
        var startY = 0f
        val CLICK_THRESHOLD = 10

        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dX = view.x - event.rawX
                    dY = view.y - event.rawY
                    startX = event.rawX
                    startY = event.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    view.animate()
                        .x(event.rawX + dX)
                        .y(event.rawY + dY)
                        .setDuration(0)
                        .start()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val endX = event.rawX
                    val endY = event.rawY
                    if (Math.abs(endX - startX) < CLICK_THRESHOLD && Math.abs(endY - startY) < CLICK_THRESHOLD) {
                        onClickAction()
                    }
                    val prefs = getSharedPreferences("UI_PREFS", Context.MODE_PRIVATE)
                    prefs.edit().putFloat(keyX, view.x).putFloat(keyY, view.y).apply()
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Verificação de segurança - encerra imediatamente se detetar ameaça
        if (SecurityGuard.runAllChecks(this) || SecurityGuard.isSignatureTampered(this)) {
            finishAffinity()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        makeDraggable(binding.btnToggleKeyboard, "KEYBOARD_BTN_X", "KEYBOARD_BTN_Y") {
            isKeyboardMode = !isKeyboardMode
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            if (isKeyboardMode) {
                binding.btnToggleKeyboard.setImageResource(android.R.drawable.ic_menu_camera)
                binding.webview.evaluateJavascript("window._isManualMode = true; var el = document.getElementById('eansuche'); if (el) el.focus();", null)
                imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
            } else {
                binding.btnToggleKeyboard.setImageResource(R.drawable.ic_keyboard)
                binding.webview.evaluateJavascript("window._isManualMode = false; var el = document.getElementById('eansuche'); if (el) el.blur();", null)
                imm.hideSoftInputFromWindow(binding.webview.windowToken, 0)
            }
        }

        binding.btnToggleKeyboard.post {
            val prefs = getSharedPreferences("UI_PREFS", Context.MODE_PRIVATE)
            if (prefs.contains("KEYBOARD_BTN_X") && prefs.contains("KEYBOARD_BTN_Y")) {
                binding.btnToggleKeyboard.x = prefs.getFloat("KEYBOARD_BTN_X", binding.btnToggleKeyboard.x)
                binding.btnToggleKeyboard.y = prefs.getFloat("KEYBOARD_BTN_Y", binding.btnToggleKeyboard.y)
            }
        }

        checkAndRequestCameraPermission()

        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (binding.layoutOffline.visibility == View.VISIBLE) {
                        loadInitialUrl()
                    }
                }
            }
        }
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)

        // Pré-carregar o ML Kit em background para abrir a câmara instantaneamente depois
        Thread { ScannerInstance.client }.start()

        // Verificar atualizações automaticamente ao abrir a app (silencioso, sem mostrar erros)
        checkForUpdatesOnStartup()

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }


        binding.btnRetry.setOnClickListener {
            loadInitialUrl()
        }

        binding.btnHome.setOnClickListener {
            binding.webview.loadUrl(START_URL)
        }

        binding.btnLogout.setOnClickListener {
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle("Abmelden")
                .setMessage("Möchten Sie sich wirklich abmelden und die App schließen?")
                .setPositiveButton("Ja") { _, _ ->
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    binding.webview.clearCache(true)
                    binding.webview.clearFormData()
                    binding.webview.clearHistory()
                    Toast.makeText(this@MainActivity, "Abgemeldet", Toast.LENGTH_SHORT).show()
                    finishAffinity()
                }
                .setNegativeButton("Nein", null)
                .show()
        }

        // Forçar as cores da "rodinha" de refresh e da barra de progresso para VERDE absoluto
        binding.swipeRefresh.setColorSchemeColors(android.graphics.Color.parseColor("#8BC34A"))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            binding.progressBar.progressTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#8BC34A"))
        }

        binding.swipeRefresh.setOnRefreshListener {
            loadInitialUrl()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.visibility == View.VISIBLE && binding.webview.canGoBack()) {
                    binding.webview.goBack()
                } else {
                    if (System.currentTimeMillis() - backPressedTime < 2000) {
                        finish()
                    } else {
                        Toast.makeText(this@MainActivity, "Zum Beenden erneut Zurück drücken", Toast.LENGTH_SHORT).show()
                        backPressedTime = System.currentTimeMillis()
                    }
                }
            }
        })

        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    showSettingsDialog()
                    true
                }
                else -> false
            }
        }

        binding.webview.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true    // Guarda o login no LocalStorage
            databaseEnabled = true      // Permite o uso de WebSQL/IndexedDB
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = false     // Recomendação de segurança: false se não usar ficheiros locais no HTML
            allowContentAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT // Usa cache normal com revalidação
            userAgentString = userAgentString + " WebApp/Android"
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            // Melhorias de performance
            loadsImagesAutomatically = true
            blockNetworkImage = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        // Renderizacao por hardware (mais rapido e fluido)
        binding.webview.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webview, true)

        binding.webview.addJavascriptInterface(object {
            @JavascriptInterface
            fun scanCode() {
                runOnUiThread { startScan() }
            }
            @JavascriptInterface
            fun retryConnection() {
                runOnUiThread { loadInitialUrl() }
            }
            @JavascriptInterface
            fun debugPage(html: String) {
                runOnUiThread {
                    // Copiar para clipboard
                    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("debug_html", html))
                    // Mostrar dialog com preview
                    val preview = if (html.length > 1000) html.substring(0, 1000) + "..." else html
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("DEBUG - HTML copiado!")
                        .setMessage(preview)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }, "Android")

        binding.webview.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                binding.progressBar.progress = newProgress
                if (newProgress >= 100) {
                    binding.progressBar.visibility = View.GONE
                } else {
                    if (!binding.swipeRefresh.isRefreshing) {
                        binding.progressBar.visibility = View.VISIBLE
                    }
                }
            }

            override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                this@MainActivity.filePathCallback = filePathCallback
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                } else {
                    openImageChooser()
                }
                return true
            }
        }

        binding.webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:")) {
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: ActivityNotFoundException) {}
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                binding.layoutOffline.visibility = View.GONE
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                if (binding.layoutOffline.visibility == View.GONE) {
                    binding.webview.visibility = View.VISIBLE
                }
                binding.webview.evaluateJavascript(JS_AUTO_SCAN_TRIGGER, null)

                // Adiciona margem inferior no corpo do site para os botões não taparem o botão 'Abschliessen'
                val jsPadding = "document.body.style.paddingBottom = '80px';"
                binding.webview.evaluateJavascript(jsPadding, null)
                
                // Ocultar os botões na página de login
                if (url != null && url.contains("loginform.php")) {
                    binding.bottomButtons.visibility = View.GONE
                } else {
                    binding.bottomButtons.visibility = View.VISIBLE
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showOfflineState()
                }
            }

            // Prevenção de Ecrã Branco (Crash Recovery)
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                if (view != null && view.parent != null) {
                    (view.parent as android.view.ViewGroup).removeView(view)
                    view.destroy()
                }
                // Como não podemos recriar a WebView facilmente sem inflar de novo,
                // a melhor solução é reiniciar a Activity de forma transparente
                recreate()
                return true
            }
        }

        loadInitialUrl()
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            else -> false
        }
    }

    private fun loadInitialUrl() {
        if (isNetworkAvailable()) {
            binding.webview.visibility = View.VISIBLE
            binding.layoutOffline.visibility = View.GONE
            val currentUrl = binding.webview.url
            if (currentUrl != null && currentUrl != "about:blank") {
                binding.webview.reload()
            } else {
                binding.webview.loadUrl(START_URL)
            }
        } else {
            showOfflineState()
        }
    }

    private fun showOfflineState() {
        binding.webview.visibility = View.GONE
        binding.progressBar.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.layoutOffline.visibility = View.VISIBLE
    }

    private fun openImageChooser() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        cameraPhotoUri = createImageUri()
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)

        val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
        contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
        contentSelectionIntent.type = "image/*"

        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
            putExtra(Intent.EXTRA_TITLE, getString(R.string.select_image_title))
            putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
        }
        fileChooserLauncher.launch(chooserIntent)
    }

    private fun createImageUri(): Uri {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val imageFileName = "IMG_" + timeStamp + ".jpg"
        val storageDir = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "Camera")
        if (!storageDir.exists()) storageDir.mkdirs()
        val file = File(storageDir, imageFileName)
        return FileProvider.getUriForFile(this, "ch.greenonline.ernstmeier.fileprovider", file)
    }

    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        isScanning = false
        if (result.resultCode == RESULT_OK) {
            val scanned = result.data?.getStringExtra("SCANNED_CODE")
            if (scanned != null) {
                vibrateOnSuccess()
                val script = JS_INJECT_SCAN_RESULT.replace("%SCANNED_CODE%", scanned)
                // Execute only the lightweight script via evaluateJavascript without triggering a heavy UI refresh
                binding.webview.evaluateJavascript(script, null)
            }
        }
    }

    private fun startScan() {
        if (isScanning) return
        isScanning = true
        scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
    }

    private fun vibrateOnSuccess() {
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private val GITHUB_OWNER = "Rafaellapa20"
    private val GITHUB_REPO = "ScanMeier"

    private fun isVersionNewer(current: String, latest: String): Boolean {
        try {
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
            val maxLength = maxOf(currentParts.size, latestParts.size)
            for (i in 0 until maxLength) {
                val currentVal = currentParts.getOrNull(i) ?: 0
                val latestVal = latestParts.getOrNull(i) ?: 0
                if (latestVal > currentVal) return true
                if (latestVal < currentVal) return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun showSettingsDialog() {
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }
        val options = arrayOf("Nach Updates suchen", "Cache und Cookies löschen")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Einstellungen (v$currentVersion)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> checkForUpdates()
                    1 -> clearAppCache()
                }
            }
            .show()
    }

    private fun clearAppCache() {
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        binding.webview.clearCache(true)
        binding.webview.clearFormData()
        binding.webview.clearHistory()
        Toast.makeText(this, "Cache und Cookies gelöscht!", Toast.LENGTH_SHORT).show()
        binding.webview.loadUrl(START_URL)
    }

    private fun checkForUpdates() {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Suche nach Updates...")
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val p1 = android.util.Base64.decode("aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                val p2 = android.util.Base64.decode("UmFmYWVsbGFwYTIwL1NjYW5NZWllci9tYWlu", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                val p3 = android.util.Base64.decode("L3VwZGF0ZS5qc29u", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                val url = p1 + p2 + p3
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connect()

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    val latestVersion = json.getString("version").trim()
                    val downloadUrl = json.getString("url")
                    
                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName

                    runOnUiThread {
                        progressDialog.dismiss()
                        if (isVersionNewer(currentVersion, latestVersion)) {
                            showUpdateAvailableDialog(latestVersion, downloadUrl)
                        } else {
                            Toast.makeText(this, "App ist auf dem neuesten Stand (v$currentVersion)", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    runOnUiThread {
                        progressDialog.dismiss()
                        Toast.makeText(this, "Fehler bei der Suche (HTTP ${connection.responseCode})", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Verbindungsfehler: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun showUpdateAvailableDialog(newVersion: String, downloadUrl: String, mandatory: Boolean = false) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Update verfügbar")
            .setMessage("Eine neue Version ($newVersion) ist verfügbar. Bitte aktualisieren Sie die App, um fortzufahren.")
            .setCancelable(false)
            .setPositiveButton("Jetzt aktualisieren") { _, _ ->
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    Toast.makeText(this, "Bitte erlauben Sie das Installieren unbekannter Apps und versuchen Sie es erneut.", Toast.LENGTH_LONG).show()
                } else {
                    startUpdateDownload(downloadUrl)
                }
            }
        if (!mandatory) {
            builder.setNegativeButton("Später", null)
        }
        builder.show()
    }

    // Verificação ao abrir a app - bloqueia imediatamente se desativada, verifica update em background
    private fun checkForUpdatesOnStartup() {
        Thread {
            try {
                // URL montado em runtime para não ser visível em texto simples
                val p1 = android.util.Base64.decode("aHR0cHM6Ly9yYXcuZ2l0aHVidXNlcmNvbnRlbnQuY29tL", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                val p2 = android.util.Base64.decode("UmFmYWVsbGFwYTIwL1NjYW5NZWllci9tYWlu", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                val p3 = android.util.Base64.decode("L3VwZGF0ZS5qc29u", android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                val url = p1 + p2 + p3
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.connect()

                if (connection.responseCode == 200) {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = org.json.JSONObject(response)
                    val currentVersion = packageManager.getPackageInfo(packageName, 0).versionName
                    val latestVersion = json.getString("version").trim()
                    val downloadUrl = json.getString("url")
                    val hasUpdate = isVersionNewer(currentVersion, latestVersion)

                    // 1. Verificar se a app está desativada manualmente
                    val isActive = if (json.has("active")) json.getBoolean("active") else true

                    // 2. Verificar data de expiração automática
                    var isExpired = false
                    if (json.has("expires")) {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            val expireDate = sdf.parse(json.getString("expires"))
                            if (expireDate != null && java.util.Date().after(expireDate)) isExpired = true
                        } catch (_: Exception) {}
                    }

                    val msg = if (json.has("inactive_message")) json.getString("inactive_message") else "Offline"

                    runOnUiThread {
                        if (!isActive || isExpired) {
                            // Bloqueia a app imediatamente - mostra "Offline"
                            // Se houver update disponível, aparece o diálogo de update por cima
                            showAppDisabledDialog(msg)
                            if (hasUpdate) {
                                showUpdateAvailableDialog(latestVersion, downloadUrl, mandatory = true)
                            }
                        } else if (hasUpdate) {
                            // App ativa e com update disponível
                            showUpdateAvailableDialog(latestVersion, downloadUrl, mandatory = true)
                        }
                    }
                }
            } catch (_: Exception) {
                // Silencioso - se não houver internet ou erro, a app continua normalmente
            }
        }.start()
    }

    // Diálogo bloqueante que não pode ser fechado - app offline
    private fun showAppDisabledDialog(message: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Verbindungsfehler")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK") { _, _ -> finishAffinity() }
            .show()
    }

    private fun startUpdateDownload(downloadUrl: String) {
        val progressDialog = android.app.ProgressDialog(this).apply {
            setMessage("Update wird heruntergeladen...")
            setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL)
            max = 100
            setCancelable(false)
            show()
        }

        Thread {
            try {
                val connection = java.net.URL(downloadUrl).openConnection() as java.net.HttpURLConnection
                connection.connect()
                val fileLength = connection.contentLength
                val input = connection.inputStream
                
                val updateDir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Updates")
                if (!updateDir.exists()) updateDir.mkdirs()
                val apkFile = File(updateDir, "update.apk")
                val output = apkFile.outputStream()

                val data = ByteArray(4096)
                var total: Long = 0
                var count: Int
                while (input.read(data).also { count = it } != -1) {
                    total += count
                    if (fileLength > 0) {
                        runOnUiThread {
                            progressDialog.progress = (total * 100 / fileLength).toInt()
                        }
                    }
                    output.write(data, 0, count)
                }

                output.flush()
                output.close()
                input.close()

                runOnUiThread {
                    progressDialog.dismiss()
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this, "Download-Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        try {
            val apkUri = FileProvider.getUriForFile(this, "ch.greenonline.ernstmeier.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Installation fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        networkCallback?.let {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(it)
        }
        binding.webview.apply {
            loadUrl("about:blank")
            stopLoading()
            destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val START_URL = "https://greenonline.ch/intpl/php/loginform.php?ownerIdNr=000149&version=11&titel=Ernst%20Meier%20AG%20Web-App"

        private val JS_AUTO_SCAN_TRIGGER = """
            (function() {
                window._isManualMode = window._isManualMode || false;
                
                // Alvo exato: o campo 'bereit' tem id="eansuche" neste site
                function hookEanSuche() {
                    var el = document.getElementById('eansuche');
                    if (!el || el.dataset.bereitHooked) return;
                    el.dataset.bereitHooked = '1';

                    function openScanner(e) {
                        if (window._isManualMode) return;
                        
                        e.preventDefault();
                        e.stopImmediatePropagation();
                        try { el.blur(); } catch(ex) {}
                        Android.scanCode();
                    }

                    // Substituir o onclick original (que chama hideKeyboard) pelo nosso scanner
                    el.onclick = openScanner;
                    el.addEventListener('touchstart', openScanner, { capture: true, passive: false });
                    el.addEventListener('focus', openScanner, { capture: true });
                    el.addEventListener('mousedown', openScanner, { capture: true });
                }

                hookEanSuche();
                setTimeout(hookEanSuche, 500);
                setTimeout(hookEanSuche, 1500);

                // Vigiar DOM para quando a pagina carregar o campo (AJAX)
                if (window._bereitObserver) {
                    try { window._bereitObserver.disconnect(); } catch(e) {}
                }
                window._bereitObserver = new MutationObserver(hookEanSuche);
                window._bereitObserver.observe(document.documentElement, { childList: true, subtree: true });
            })();
        """.trimIndent()

        private val JS_INJECT_SCAN_RESULT = """
            (function() {
                const code = '%SCANNED_CODE%';
                
                function injectValueAndSubmit(el) {
                    if (!el) return false;
                    
                    // 1. Set the value
                    el.value = code;
                    
                    // 2. Notify frameworks (React, Vue, etc.)
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                    
                    // 3. Simulate Enter Key sequence (keydown, keypress, keyup)
                    const eventOpts = { bubbles: true, cancelable: true, keyCode: 13, which: 13, key: 'Enter', code: 'Enter' };
                    el.dispatchEvent(new KeyboardEvent('keydown', eventOpts));
                    el.dispatchEvent(new KeyboardEvent('keypress', eventOpts));
                    el.dispatchEvent(new KeyboardEvent('keyup', eventOpts));

                    // 4. Force Form Submission
                    if (el.form) {
                        // Look for a submit button and click it to trigger validation
                        const submitBtn = el.form.querySelector('input[type="submit"], button[type="submit"], .btn-submit');
                        if (submitBtn) {
                            submitBtn.click();
                        } else {
                            // Dispatch submit event (triggers onsubmit handlers)
                            const submitEvent = new Event('submit', { bubbles: true, cancelable: true });
                            if (el.form.dispatchEvent(submitEvent)) {
                                el.form.submit();
                            }
                        }
                    }
                    
                    // Highlight effect for confirmation
                    const originalBG = el.style.backgroundColor;
                    el.style.backgroundColor = '#e8f0fe';
                    setTimeout(() => { if(el) el.style.backgroundColor = originalBG; }, 500);
                    
                    return true;
                }

                // Strategy 1: Specific selectors (common in your site)
                const selectors = ['.barcode-input', '#barcode', '[name="barcode"]', '#search', '[type="search"]'];
                for (let s of selectors) {
                    let el = document.querySelector(s);
                    if (el && el.offsetParent !== null) {
                        if (injectValueAndSubmit(el)) return;
                    }
                }

                // Strategy 2: Active element
                if (document.activeElement && (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA')) {
                    if (injectValueAndSubmit(document.activeElement)) return;
                }

                // Strategy 3: First visible text-like input
                const inputs = document.querySelectorAll('input[type=text], input[type=tel], input[type=number], textarea');
                for (let i = 0; i < inputs.length; i++) {
                    if (inputs[i].offsetParent !== null) {
                        if (injectValueAndSubmit(inputs[i])) return;
                    }
                }
            })();
        """.trimIndent()

        private val JS_INJECT_CHECK = """
            (function() {
                let targetElement = document.activeElement;
                if (!targetElement || (targetElement.tagName !== 'INPUT' && targetElement.tagName !== 'TEXTAREA')) {
                    const inputs = document.querySelectorAll('input[type=text], input[type=search], input[type=tel], input[type=number], textarea');
                    for (let i = 0; i < inputs.length; i++) {
                        if (inputs[i].offsetParent !== null) {
                            targetElement = inputs[i];
                            break;
                        }
                    }
                }

                if (targetElement) {
                    if (targetElement.form) {
                         const submitBtn = targetElement.form.querySelector('input[type="submit"], button[type="submit"]');
                         if (submitBtn) submitBtn.click(); else targetElement.form.submit();
                    } else {
                        const enterEvent = new KeyboardEvent('keydown', { bubbles: true, cancelable: true, keyCode: 13 });
                        targetElement.dispatchEvent(enterEvent);
                    }
                }
            })();
        """.trimIndent()

    }
}
