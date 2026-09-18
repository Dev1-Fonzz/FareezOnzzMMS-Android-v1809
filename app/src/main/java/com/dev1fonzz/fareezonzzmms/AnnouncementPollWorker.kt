package com.dev1fonzz.fareezonzzmms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * ============================================================
 *  Dev1Fonzz — Polling Pengumuman (Pilihan B)
 * ============================================================
 *  TIADA Firebase. Guna terus Google Sheets API v4 (read-only,
 *  key sama macam VITE_GOOGLE_SHEETS_API_KEY web kau) — SAMA
 *  endpoint yang src/services/GoogleSheetsAPI.js -> fetchSheet()
 *  panggil. Backend/Apps Script kau LANGSUNG TAK DISENTUH.
 *
 *  Dijadualkan oleh FozApplication setiap ~15 minit (had minimum
 *  WorkManager/Android untuk periodic background work — bukan
 *  had yang kami pilih).
 *
 *  Penapisan (sengaja mudah & selamat, bukan meniru logik penuh
 *  getAnnouncements() di web sebab tu perlukan tier ahli yang
 *  sedang log masuk):
 *    STATUS == "DISIARKAN"  DAN  TARGET_AUDIENCE == "SEMUA"
 *  — iaitu pengumuman untuk SEMUA ahli sahaja. Pengumuman
 *  khusus VIP/VVIP/JK tak akan trigger notification native
 *  (ahli tetap nampak dalam app macam biasa bila buka).
 * ============================================================
 */
class AnnouncementPollWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val apiKey = Constants.GOOGLE_SHEETS_API_KEY
        val sheetId = Constants.GOOGLE_SHEETS_ID
        if (apiKey.isBlank() || sheetId.isBlank()) {
            // Belum dikonfigur lagi (lihat Constants.kt) — diskik senyap
            return Result.success()
        }

        return try {
            val rows = fetchAnnouncementRows(sheetId, apiKey)
            val fresh = rows.filter {
                it["STATUS"] == "DISIARKAN" && it["TARGET_AUDIENCE"] == "SEMUA"
            }

            val prefs = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val seen = prefs.getStringSet(Constants.PREF_SEEN_ANNOUNCEMENTS, emptySet()) ?: emptySet()
            val seenMutable = seen.toMutableSet()

            val newOnes = fresh.filter { row ->
                val uid = row["ANNOUNCEMENT_UID"]
                !uid.isNullOrBlank() && uid !in seenMutable
            }

            // Notification cuma untuk yang benar-benar baru, had 5 setiap check
            // (elak banjir notification kalau app lama tak dibuka)
            newOnes.take(5).forEach { row -> showNotification(row) }

            fresh.forEach { row -> row["ANNOUNCEMENT_UID"]?.let { seenMutable.add(it) } }
            // Had storan — simpan 200 UID terkini sahaja
            val trimmed = if (seenMutable.size > 200) seenMutable.toList().takeLast(200).toSet() else seenMutable
            prefs.edit().putStringSet(Constants.PREF_SEEN_ANNOUNCEMENTS, trimmed).apply()

            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    private fun fetchAnnouncementRows(sheetId: String, apiKey: String): List<Map<String, String>> {
        val url = URL(
            "https://sheets.googleapis.com/v4/spreadsheets/$sheetId/values/" +
                "${Constants.SHEET_ANNOUNCEMENT}?key=$apiKey&majorDimension=ROWS"
        )
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        // Diperlukan Google bila API key kau di-restrict kepada "Android apps"
        // (package + SHA-1 signing cert). Tiada kesan kalau key kau tak
        // di-restrict lagi — header ni cuma diabaikan.
        conn.setRequestProperty("X-Android-Package", applicationContext.packageName)
        signingCertSha1()?.let { conn.setRequestProperty("X-Android-Cert", it) }

        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        val json = JSONObject(body)
        val values = json.optJSONArray("values") ?: return emptyList()
        if (values.length() < 2) return emptyList()

        val headers = (0 until values.getJSONArray(0).length()).map {
            values.getJSONArray(0).getString(it).trim()
        }

        return (1 until values.length()).map { r ->
            val row = values.getJSONArray(r)
            headers.mapIndexed { i, h -> h to (if (i < row.length()) row.optString(i, "") else "") }.toMap()
        }
    }

    /** Ambil SHA-1 sijil signing APK yang sedang berjalan — untuk header X-Android-Cert */
    private fun signingCertSha1(): String? {
        return try {
            val pm = applicationContext.packageManager
            val pkg = applicationContext.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures
            }
            val cert = signatures?.firstOrNull() ?: return null
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
            digest.joinToString(":") { String.format("%02X", it) }
        } catch (e: Exception) {
            null
        }
    }

    private fun showNotification(row: Map<String, String>) {
        val title = row["TITLE"]?.takeIf { it.isNotBlank() } ?: applicationContext.getString(R.string.notif_channel_announcements_name)
        val content = row["CONTENT"]?.takeIf { it.isNotBlank() } ?: ""

        val targetIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data = Uri.parse("fozmms://open/announcements")
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, Random.nextInt(), targetIntent, flags)

        val notification = NotificationCompat.Builder(applicationContext, applicationContext.getString(R.string.notif_channel_announcements))
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(applicationContext, R.color.foz_red2))
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(applicationContext).notify(Random.nextInt(), notification)
        } catch (e: SecurityException) {
            // Kebenaran POST_NOTIFICATIONS belum diberi — diskik senyap
        }
    }
}
