package com.dev1fonzz.fareezonzzmms

import android.Manifest
import android.app.DownloadManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.dev1fonzz.fareezonzzmms.BuildConfig
import com.dev1fonzz.fareezonzzmms.databinding.ActivityMainBinding
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // --- File chooser (upload gambar profil / kad keahlian) ---
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraCaptureUri: Uri? = null
    private var pendingCameraPermissionAction: (() -> Unit)? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        val results: Array<Uri>? = when {
            result.resultCode != RESULT_OK -> null
            data?.dataString != null -> arrayOf(Uri.parse(data.dataString))
            cameraCaptureUri != null -> arrayOf(cameraCaptureUri as Uri)
            else -> null
        }
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
        cameraCaptureUri = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCameraPermissionAction?.invoke()
        } else {
            Toast.makeText(this, getString(R.string.permission_camera_denied), Toast.LENGTH_SHORT).show()
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
        pendingCameraPermissionAction = null
    }

    private var doubleBackToExit = false

    // --- Native Diagnostics Bridge (dipanggil dari web — lihat DiagnosticsBridge.kt) ---
    private lateinit var diagnosticsBridge: DiagnosticsBridge
    private val locationHandler = Handler(Looper.getMainLooper())

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            fetchLocationOnce()
        } else {
            diagnosticsBridge.deliverLocationResult(
                JSONObject().put("granted", false).put("reason", "permission_denied")
            )
        }
    }

    // --- QR / barcode scanner ---
    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val json = JSONObject()
        if (result.contents != null) {
            json.put("granted", true)
            json.put("content", result.contents)
            json.put("format", result.formatName ?: "")
        } else {
            json.put("granted", false)
            json.put("reason", "cancelled")
        }
        diagnosticsBridge.deliverQrResult(json)
    }

    // --- Push notification permission (Android 13+) — tak kesan token, cuma paparan ---
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* tiada aksi khas — pengguna boleh benarkan kemudian dalam Settings */ }

    // --- Biometric quick-unlock ---
    private var biometricAuthenticated = false
    private var suppressLockOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupSwipeRefresh()
        setupBackNavigation()
        requestNotificationPermissionIfNeeded()

        binding.btnRetry.setOnClickListener { hardReload() }
        binding.btnUnlock.setOnClickListener { showBiometricPrompt() }

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else if (intent?.data != null) {
            handleIntent(intent)
        } else {
            loadStart()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** Deep link (App Links https://... atau custom scheme fozmms://open/<laluan>) */
    private fun handleIntent(intent: Intent?) {
        val path = intent?.data?.path
        if (!path.isNullOrBlank()) {
            binding.offlineLayout.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.webView.loadUrl(Constants.BASE_URL + path)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ============================================================
    //  WebView — teras aplikasi
    // ============================================================
    private fun setupWebView() {
        val webView = binding.webView
        val settings = webView.settings

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.userAgentString = settings.userAgentString + Constants.UA_SUFFIX
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        // Sembunyikan "tanda" WebView — elak scrollbar gaya Chrome generik
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        // Sekat menu context-menu browser (Open in new tab / Copy link address /
        // Download image) bila long-press link atau imej — ni paling dedahkan
        // yang app ni WebView. Copy/paste dalam medan input (login, dsb) TAK
        // disekat sebab tu penting untuk UX.
        webView.setOnLongClickListener {
            when (webView.hitTestResult.type) {
                android.webkit.WebView.HitTestResult.EDIT_TEXT_TYPE,
                android.webkit.WebView.HitTestResult.UNKNOWN_TYPE -> false
                else -> true
            }
        }

        // Elak WebView boleh di-inspect via Chrome DevTools (USB debugging)
        // pada build release — nampak lagi "sealed", bukan sekadar browser tab
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        }

        // Sesi kekal (login persist) — sama macam browser
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = FozWebViewClient()
        webView.webChromeClient = FozWebChromeClient()
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            startDownload(url, contentDisposition, mimeType)
        }

        // Jambatan JS <-> Native untuk sokongan lanjut (device info, lokasi
        // sekali-shot, clipboard, redirect sokongan). Lihat DiagnosticsBridge.kt.
        diagnosticsBridge = DiagnosticsBridge(this, webView)
        webView.addJavascriptInterface(diagnosticsBridge, "AndroidDiagnostics")
    }

    private fun loadStart() {
        binding.offlineLayout.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.loadUrl(Constants.BASE_URL + Constants.START_PATH)
    }

    /**
     * "Reload keras" — buang cache WebView dulu sebelum muat semula.
     * Berbeza dari webView.reload() / window.location.reload() dalam
     * page (yang boleh terperangkap ambil semula fail JS/CSS lama yang
     * sama dari cache WebView sendiri kalau server dah deploy versi
     * baru). Guna ini untuk "Cuba Lagi" & tarik-untuk-segar (pull to
     * refresh) supaya benar-benar dapat versi terkini dari server.
     */
    private fun hardReload() {
        val current = binding.webView.url?.takeIf { it.isNotBlank() }
            ?: (Constants.BASE_URL + Constants.START_PATH)
        binding.webView.clearCache(true)
        binding.offlineLayout.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
        binding.webView.loadUrl(current)
    }

    private inner class FozWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host ?: return false
            val isAllowed = Constants.ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }
            if (isAllowed) return false

            // Skim lain (tel:, mailto:, wa.me, t.me, dsb) — buka guna app luaran
            return try {
                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                true
            } catch (e: Exception) {
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            binding.topProgress.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            binding.topProgress.visibility = View.GONE
            binding.swipeRefresh.isRefreshing = false
            CookieManager.getInstance().flush()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            super.onReceivedError(view, request, error)
            if (request.isForMainFrame) showOffline()
        }

        // Legacy overload — satu-satunya yang dipanggil pada Android 5.0/5.1 (API 21-22)
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?
        ) {
            super.onReceivedError(view, errorCode, description, failingUrl)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showOffline()
        }
    }

    private inner class FozWebChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            binding.topProgress.progress = newProgress
            if (newProgress >= 100) binding.topProgress.visibility = View.GONE
        }

        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback

            val launchChooser = {
                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                var cameraIntent: Intent? = null
                try {
                    val captureDir = (getExternalFilesDir("captures") ?: cacheDir).apply { mkdirs() }
                    val photoFile = File.createTempFile("FOZ_", ".jpg", captureDir)
                    cameraCaptureUri = FileProvider.getUriForFile(
                        this@MainActivity, "$packageName.fileprovider", photoFile
                    )
                    cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, cameraCaptureUri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    }
                } catch (e: Exception) {
                    cameraCaptureUri = null
                }

                val chooser = Intent.createChooser(galleryIntent, null).apply {
                    if (cameraIntent != null) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
                    }
                }
                suppressLockOnce = true
                fileChooserLauncher.launch(chooser)
            }

            if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pendingCameraPermissionAction = launchChooser
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            } else {
                launchChooser()
            }
            return true
        }
    }

    // ============================================================
    //  Muat turun (kad keahlian / resit / lampiran)
    // ============================================================
    private fun startDownload(url: String, contentDisposition: String?, mimeType: String?) {
        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                val cookies = CookieManager.getInstance().getCookie(url)
                addRequestHeader("cookie", cookies)
                addRequestHeader("User-Agent", binding.webView.settings.userAgentString)
                setMimeType(mimeType)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val fileName = android.webkit.URLUtil.guessFileName(url, contentDisposition, mimeType)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }
            val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, getString(R.string.download_started), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_desc), Toast.LENGTH_SHORT).show()
        }
    }

    // ============================================================
    //  Swipe-to-refresh — dilumpuhkan bila halaman web sedang di-scroll
    // ============================================================
    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(android.R.color.white)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.foz_red2)
        binding.swipeRefresh.setOnRefreshListener { hardReload() }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.webView.canScrollVertically(-1)
        }
    }

    // ============================================================
    //  Navigasi belakang — ikut sejarah WebView, double-tap untuk keluar
    // ============================================================
    private fun setupBackNavigation() {
        onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    doubleBackToExit -> finish()
                    else -> {
                        doubleBackToExit = true
                        Toast.makeText(this@MainActivity, getString(R.string.exit_confirm), Toast.LENGTH_SHORT).show()
                        binding.root.postDelayed({ doubleBackToExit = false }, 2000)
                    }
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)
    }

    private lateinit var onBackPressedCallback: OnBackPressedCallback

    // ============================================================
    //  Status sambungan
    // ============================================================
    private fun showOffline() {
        binding.webView.visibility = View.GONE
        binding.topProgress.visibility = View.GONE
        binding.swipeRefresh.isRefreshing = false
        binding.offlineLayout.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ============================================================
    //  Lokasi diagnostik — sekali-shot, dipanggil hanya dari web
    //  (window.AndroidDiagnostics.requestLocation()). Tiada langganan
    //  berterusan, dibuang serta-merta selepas satu bacaan/timeout.
    // ============================================================
    fun requestDiagnosticLocation() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine || hasCoarse) {
            fetchLocationOnce()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
        }
    }

    private fun fetchLocationOnce() {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val providers = listOfNotNull(
            LocationManager.GPS_PROVIDER.takeIf { lm.isProviderEnabled(it) },
            LocationManager.NETWORK_PROVIDER.takeIf { lm.isProviderEnabled(it) }
        )
        if (providers.isEmpty()) {
            diagnosticsBridge.deliverLocationResult(
                JSONObject().put("granted", true).put("reason", "location_disabled")
            )
            return
        }

        var delivered = false
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (delivered) return
                delivered = true
                lm.removeUpdates(this)
                locationHandler.removeCallbacksAndMessages(null)
                resolveAndDeliver(location)
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }

        try {
            // Cuba bacaan terakhir dahulu — laju, cukup tepat untuk sokongan
            val lastKnown = providers.mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 5 * 60 * 1000) {
                delivered = true
                resolveAndDeliver(lastKnown)
                return
            }

            providers.forEach { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
            // Had masa 10 saat — elak app "hang" tunggu GPS dalam bangunan
            locationHandler.postDelayed({
                if (!delivered) {
                    delivered = true
                    lm.removeUpdates(listener)
                    diagnosticsBridge.deliverLocationResult(
                        JSONObject().put("granted", true).put("reason", "timeout")
                    )
                }
            }, 10_000L)
        } catch (e: SecurityException) {
            diagnosticsBridge.deliverLocationResult(
                JSONObject().put("granted", false).put("reason", "permission_denied")
            )
        }
    }

    private fun resolveAndDeliver(location: Location) {
        Thread {
            val result = JSONObject()
            result.put("granted", true)
            result.put("lat", location.latitude)
            result.put("lon", location.longitude)
            result.put("accuracy", location.accuracy)
            try {
                @Suppress("DEPRECATION")
                val geocoder = Geocoder(this, Locale("ms", "MY"))
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                val address = addresses?.firstOrNull()?.getAddressLine(0)
                result.put("address", address ?: JSONObject.NULL)
            } catch (e: Exception) {
                result.put("address", JSONObject.NULL)
            }
            diagnosticsBridge.deliverLocationResult(result)
        }.start()
    }

    // ============================================================
    //  QR / Barcode scanner — dipanggil dari web (AndroidDiagnostics.scanQrCode())
    // ============================================================
    fun launchQrScanner() {
        val hasCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCam) {
            pendingCameraPermissionAction = { launchQrScannerInternal() }
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        } else {
            launchQrScannerInternal()
        }
    }

    private fun launchQrScannerInternal() {
        suppressLockOnce = true
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
            setPrompt("Imbas kod QR / bar kad keahlian")
            setBeepEnabled(false)
            setOrientationLocked(true)
            captureActivity = CaptureActivity::class.java
        }
        qrScanLauncher.launch(options)
    }

    // ============================================================
    //  Kunci biometric — dipanggil bila app kembali dari latar belakang,
    //  aktif hanya jika ahli enable dalam Tetapan di web
    //  (AndroidDiagnostics.setBiometricLockEnabled(true)).
    // ============================================================
    fun isBiometricAvailable(): Boolean {
        val bm = BiometricManager.from(this)
        return bm.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun isBiometricLockEnabled(): Boolean {
        return getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
            .getBoolean(Constants.PREF_BIOMETRIC_ENABLED, false)
    }

    private fun showLock() {
        binding.lockLayout.visibility = View.VISIBLE
        binding.webView.visibility = View.GONE
    }

    private fun hideLock() {
        biometricAuthenticated = true
        binding.lockLayout.visibility = View.GONE
        binding.webView.visibility = View.VISIBLE
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                hideLock()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Overlay kunci kekal — ahli boleh tekan "Buka Kunci" untuk cuba lagi
            }
        })
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_prompt_title))
            .setSubtitle(getString(R.string.biometric_prompt_subtitle))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(promptInfo)
    }

    // ============================================================
    //  Lifecycle
    // ============================================================
    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        if (binding.offlineLayout.visibility == View.VISIBLE && isNetworkAvailable()) {
            loadStart()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isBiometricLockEnabled() && !biometricAuthenticated && isBiometricAvailable()) {
            showLock()
            showBiometricPrompt()
        }
    }

    override fun onStop() {
        // Elak lock semula bila cuma keluar sekejap untuk kamera/imbas QR/pilih fail
        if (suppressLockOnce) {
            suppressLockOnce = false
        } else {
            biometricAuthenticated = false
        }
        super.onStop()
    }

    override fun onPause() {
        binding.webView.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        (binding.webView.parent as? android.view.ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }
}
