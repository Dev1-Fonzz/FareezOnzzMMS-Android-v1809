package com.dev1fonzz.fareezonzzmms

/**
 * ============================================================
 *  Dev1Fonzz — Konfigurasi Pusat (CPSD)
 * ============================================================
 *  Aplikasi ni ialah pembalut (wrapper) native untuk portal ahli
 *  FareezOnzz MMS. Semua fitur/logik sebenar (login, RBAC, kad
 *  keahlian, dsb) kekal di web — app ni cuma bekalkan chrome
 *  native (splash, offline handling, muat naik/turun fail, dsb)
 *  yang WebView tak sokong secara default.
 *
 *  KEMASKINI DI SINI SAHAJA jika kau tukar domain / custom domain.
 * ============================================================
 */
object Constants {

    /** URL portal ahli. Tukar jika kau guna custom domain nanti. */
    const val BASE_URL = "https://fareezonzz-mms.vercel.app"

    /**
     * Halaman permulaan app — terus ke /auth/login (bukan /disclaimer)
     * sebab app ni khas untuk ahli sedia ada sahaja, bukan crawler SEO.
     * Jika sesi sedia ada (cookie sah), AuthContext di web akan auto
     * redirect terus ke dashboard ahli.
     */
    const val START_PATH = "/auth/login"

    /**
     * Domain yang dibenarkan dimuat DALAM WebView. Domain luar
     * (WhatsApp, Telegram, dsb) akan dibuka melalui app luaran.
     * Selaras dengan network_security_config.xml.
     */
    val ALLOWED_HOSTS = listOf(
        "fareezonzz-mms.vercel.app",
        "script.google.com",
        "script.googleusercontent.com"
    )

    /** Suffix User-Agent supaya backend/analytics boleh kenal pasti trafik dari app native */
    const val UA_SUFFIX = " FOZMMS-Android/1.0"

    // ------------------------------------------------------------
    //  Polling Pengumuman — check ANNOUNCEMENT_BOARD secara berkala
    //  (setiap ~15 minit, had minimum OS Android untuk background
    //  work, bukan pilihan kami) & papar notification native kalau
    //  ada entri baru. Guna Google Sheets API v4 TERUS — SAMA macam
    //  cara web kau baca (src/services/GoogleSheetsAPI.js fetchSheet()),
    //  bukan Apps Script, bukan Firebase.
    //
    //  NILAI SEBENAR diisi dalam local.properties (BUKAN sini) —
    //  setara .env.local di web. Salin local.properties.example ->
    //  local.properties, isi VITE_GOOGLE_SHEETS_API_KEY /
    //  VITE_GOOGLE_SHEETS_ID kau. Fail tu auto gitignored, sama
    //  konsep macam .env.local kau.
    // ------------------------------------------------------------
    val GOOGLE_SHEETS_API_KEY = BuildConfig.GOOGLE_SHEETS_API_KEY
    val GOOGLE_SHEETS_ID = BuildConfig.GOOGLE_SHEETS_ID
    const val SHEET_ANNOUNCEMENT = "ANNOUNCEMENT_BOARD"

    // --- SharedPreferences ---
    const val PREFS_NAME = "foz_prefs"
    const val PREF_BIOMETRIC_ENABLED = "biometric_lock_enabled"
    const val PREF_SEEN_ANNOUNCEMENTS = "seen_announcement_uids"
}
