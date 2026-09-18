package com.dev1fonzz.fareezonzzmms

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

/**
 * ============================================================
 *  Dev1Fonzz — Native Diagnostics Bridge
 * ============================================================
 *  Jambatan JS <-> Native untuk sokongan teknikal lanjut
 *  (contoh: modul SUPPORT_TICKET). Reka bentuk butang/UI/bila-
 *  bila-nak-panggil semua ditentukan di WEB, bukan di app ni —
 *  app cuma bekalkan kebolehan native yang browser JS tak boleh
 *  capai (lokasi tepat + alamat, info bateri/storan/rangkaian).
 *
 *  PENTING: semua fungsi di sini single-shot & user-initiated.
 *  Tiada kutipan automatik / latar belakang / berterusan.
 *
 * ------------------------------------------------------------
 *  Guna dari kod web (React):
 *
 *    if (window.AndroidDiagnostics) {
 *      // 1) Maklumat peranti — segera, tiada permission diperlukan
 *      const info = JSON.parse(window.AndroidDiagnostics.getDeviceInfo())
 *
 *      // 2) Lokasi + alamat — akan trigger permission dialog native
 *      //    bila kali pertama dipanggil. Daftar callback dulu:
 *      window.onFozLocationResult = (result) => {
 *        // result = { granted, lat, lon, accuracy, address, reason }
 *      }
 *      window.AndroidDiagnostics.requestLocation()
 *
 *      // 3) Salin terus ke papan keratan peranti (native, bukan JS clipboard API)
 *      window.AndroidDiagnostics.copyToClipboard("teks log/diagnostik...")
 *
 *      // 4) Dialog konfirmasi native + redirect ke channel sokongan
 *      //    (WhatsApp/tel/mel/URL apa-apa)
 *      window.AndroidDiagnostics.confirmAndOpen(
 *        "Hantar maklumat diagnostik ni ke sokongan?",
 *        "https://wa.me/60123456789?text=" + encodeURIComponent(info)
 *      )
 *    } else {
 *      // Jalan dalam browser biasa — sorok butang ni atau guna fallback web
 *    }
 * ============================================================
 */
class DiagnosticsBridge(
    private val activity: MainActivity,
    private val webView: WebView
) {

    @JavascriptInterface
    fun getDeviceInfo(): String {
        val ctx = activity.applicationContext
        val json = JSONObject()

        json.put("brand", Build.BRAND)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("model", Build.MODEL)
        json.put("androidVersion", Build.VERSION.RELEASE)
        json.put("sdkInt", Build.VERSION.SDK_INT)
        json.put("appVersion", BuildConfig.VERSION_NAME)
        json.put("appVersionCode", BuildConfig.VERSION_CODE)

        val dm = ctx.resources.displayMetrics
        json.put("screenWidth", dm.widthPixels)
        json.put("screenHeight", dm.heightPixels)
        json.put("densityDpi", dm.densityDpi)

        json.put("networkType", networkType(ctx))
        json.put("language", Locale.getDefault().toLanguageTag())
        json.put("timezone", TimeZone.getDefault().id)
        json.put("batteryPercent", batteryPercent(ctx))

        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            json.put("freeStorageMB", stat.availableBytes / (1024 * 1024))
            json.put("totalStorageMB", stat.totalBytes / (1024 * 1024))
        } catch (e: Exception) {
            json.put("freeStorageMB", JSONObject.NULL)
            json.put("totalStorageMB", JSONObject.NULL)
        }

        return json.toString()
    }

    @JavascriptInterface
    fun requestLocation() {
        activity.runOnUiThread { activity.requestDiagnosticLocation() }
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        activity.runOnUiThread {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("FOZ MMS", text))
            Toast.makeText(activity, "Disalin ke papan keratan", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun confirmAndOpen(message: String, url: String) {
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Sokongan Lanjut")
                .setMessage(message)
                .setPositiveButton("Hantar") { _, _ ->
                    try {
                        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Tidak dapat buka pautan sokongan", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }

    /** Kongsi teks/pautan guna Android share sheet native (contoh: kad keahlian, QR) */
    @JavascriptInterface
    fun shareText(text: String, subject: String) {
        activity.runOnUiThread {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            activity.startActivity(Intent.createChooser(send, null))
        }
    }

    /** Buka pengimbas QR/barcode native. Hasil dihantar ke window.onFozQrResult(json) */
    @JavascriptInterface
    fun scanQrCode() {
        activity.runOnUiThread { activity.launchQrScanner() }
    }

    /** Dipanggil oleh MainActivity selepas imbasan QR selesai/dibatal. */
    fun deliverQrResult(json: JSONObject) {
        val jsonText = json.toString()
        webView.post {
            webView.evaluateJavascript(
                "(function(){ if (window.onFozQrResult) { window.onFozQrResult($jsonText); } })();",
                null
            )
        }
    }

    /** Semak sokongan biometric (fingerprint/face) peranti ini */
    @JavascriptInterface
    fun isBiometricAvailable(): Boolean = activity.isBiometricAvailable()

    /** Suis kunci biometric bila app dibuka semula dari latar belakang — kawalan dari web (Tetapan Ahli) */
    @JavascriptInterface
    fun setBiometricLockEnabled(enabled: Boolean) {
        activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(Constants.PREF_BIOMETRIC_ENABLED, enabled).apply()
    }

    @JavascriptInterface
    fun isBiometricLockEnabled(): Boolean {
        return activity.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(Constants.PREF_BIOMETRIC_ENABLED, false)
    }

    /** Dipanggil oleh MainActivity selepas lokasi berjaya/gagal diperoleh. */
    fun deliverLocationResult(json: JSONObject) {
        val jsonText = json.toString()
        webView.post {
            webView.evaluateJavascript(
                "(function(){ if (window.onFozLocationResult) { window.onFozLocationResult($jsonText); } })();",
                null
            )
        }
    }

    private fun networkType(ctx: Context): String {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return "none"
            val caps = cm.getNetworkCapabilities(net) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
                else -> "other"
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            if (info?.isConnected == true) info.typeName.lowercase(Locale.US) else "none"
        }
    }

    private fun batteryPercent(ctx: Context): Int {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) {
            -1
        }
    }
}
