# FareezOnzz MMS — Android App (Native WebView Wrapper)

Projek Android Studio ni ialah **pembalut native (native wrapper)** untuk portal
ahli web sedia ada di **https://fareezonzz-mms.vercel.app**. Semua fitur/logik
sebenar (login, RBAC, dashboard, kad keahlian, dsb) kekal 100% di web —
setiap kali kau update/tambah fitur di web, app ni terus dapat perubahan
tu tanpa perlu update APK. App ni cuma tambah "chrome" native yang WebView
biasa tak ada.

## Fitur native yang dipilih untuk versi APK ni

| Fitur | Sebab dipilih |
|---|---|
| **Splash screen** bergradient jenama (merah→oren) | First impression, konsisten dengan HFUME |
| **Sesi kekal (persistent login)** — cookies disimpan & di-flush | Ahli tak perlu log masuk semula setiap kali buka app |
| **Muat naik gambar/kamera** (profil, imbas kad) | Modul `CardScanLogin` & profil ahli perlukan akses kamera/galeri |
| **Muat turun fail** (kad keahlian, resit) via DownloadManager | Bawa cookie sesi supaya fail ahli-sahaja boleh dimuat turun |
| **Skrin luar talian + Cuba Lagi** | Elak skrin putih/error teknikal bila tiada internet |
| **Bar kemajuan (loading bar) bergradient jenama** | Ganti loading bar hijau default Android |
| **Tarik-untuk-segar (pull to refresh)** — pintar, tak konflik scroll | UX standard app native |
| **Navigasi belakang ikut sejarah WebView** + tekan 2x untuk keluar | Elak app tertutup tanpa sengaja |
| **External link handling** (WhatsApp/Telegram/tel/mel) dibuka di app luar | Domain bukan portal ahli tak patut buka dalam WebView |
| **HTTPS sahaja (network security config)** | Backend Secret Protection — tiada cleartext traffic |
| **minSdk 21 (Android 5.0 Lollipop)** ke atas | Ikut permintaan — support Android 5+ |
| **Push notification pengumuman (FCM)** | Masuk terus ke notification tray phone, walau app tak dibuka |
| **QR/Barcode scanner native** | Guna kamera terus, untuk CardScanLogin & check-in event |
| **App Links + custom scheme deep link** | Pautan dari Telegram bot/SMS buka terus dalam app |
| **App Shortcuts** (tekan lama icon) | Terus ke Kad Saya / Sokongan / Pengumuman |
| **Biometric quick-unlock** | Fingerprint/face sebelum masuk, kawalan suis dari web |
| **Native Share** | Share kad/QR guna share sheet Android standard |
| **Pengumuman → notification native** | Polling berkala (bukan push) — lihat bawah |

## ⚠️ Setup TAMBAHAN diperlukan sebelum sesetengah ciri berfungsi penuh

### 1. Pengumuman jadi notification phone — TIADA Firebase, TIADA sentuh backend

App ni **check terus** sheet `ANNOUNCEMENT_BOARD` setiap ~15 minit (had minimum
Android untuk background work, bukan pilihan kami) guna **Google Sheets API v4
yang SAMA** dipanggil oleh `src/services/GoogleSheetsAPI.js -> fetchSheet()`
di web kau. Apps Script/backend kau **langsung tak disentuh/tak berubah**.

Isi 2 nilai ni dalam **`local.properties`** (BUKAN `Constants.kt`) — setara
`.env.local` di web kau, dan sama-sama auto gitignored:

```properties
# Salin dari local.properties.example -> local.properties, lepas tu isi:
GOOGLE_SHEETS_API_KEY=AIzaSy...
GOOGLE_SHEETS_ID=1a2B3c...
```

Cara paling laju dapatkan nilai sebenar: **Vercel Dashboard → Settings →
Environment Variables** (nilai `VITE_GOOGLE_SHEETS_API_KEY` /
`VITE_GOOGLE_SHEETS_ID` kau, salin terus). Gradle baca fail ni secara
automatik (`app/build.gradle`) dan suntik sebagai `BuildConfig` field —
`Constants.kt` cuma rujuk `BuildConfig`, tak simpan nilai sebenar terus
dalam source.

**(Key ni dah pun terdedah dalam bundle JS web kau sedia ada — letak dalam
app bukan risiko keselamatan baru, cuma sama level pendedahan.)**

### 🔒 Disyorkan: restrict API key khas untuk app (elak orang scan APK)

Kalau orang decompile APK, key dalam `BuildConfig` boleh dijumpai (normal
untuk semua app — bukan bug kami). Perlindungan sebenar bukan "sorok
string", tapi **had apa key tu boleh buat** di Google Cloud Console:

1. **Buat API key BAHARU** khas Android (jangan guna key web yang sama):
   Google Cloud Console → Credentials → Create Credentials → API Key
2. **Application restrictions → Android apps** → tambah:
   - Package name: `com.dev1fonzz.fareezonzzmms`
   - SHA-1 fingerprint: `keytool -list -v -keystore <keystore-kau>.jks`
3. **API restrictions** → had kepada **Google Sheets API** sahaja

App ni dah hantar header `X-Android-Package` + `X-Android-Cert` secara
automatik (dalam `AnnouncementPollWorker.kt`) supaya restriction jenis ni
berfungsi betul — walau key "bocor" dari APK, ia **tak jalan** di luar
app/keystore kau. Kalau key kau belum di-restrict lagi, header ni cuma
diabaikan (tiada kesan buruk, selamat je guna key sedia ada buat sementara).

**Had yang kau perlu tahu (jujur, bukan sorok):**
- Notification cuma untuk pengumuman `TARGET_AUDIENCE = "SEMUA"` — pengumuman
  khusus VIP/VVIP/JK tak akan trigger notification (app takde cara nak tahu
  tier ahli yang login pada peranti tu tanpa kerja tambahan). Ahli tetap
  nampak SEMUA pengumuman macam biasa bila buka app — ni cuma had untuk
  ciri notification sahaja.
- Lengah sampai ~15 minit (had OS Android untuk periodic background job,
  bukan sesuatu yang boleh kami laju-kan).
- Tanpa isi `local.properties`, app tetap jalan normal — cuma ciri
  notification senyap tak aktif (tiada crash, tiada error).

### 2. App Links (pautan https:// buka terus dalam app, bukan browser)

Host fail ni di `https://fareezonzz-mms.vercel.app/.well-known/assetlinks.json`:

```json
[{
  "relation": ["delegate_permission/common.handle_all_urls"],
  "target": {
    "namespace": "android_app",
    "package_name": "com.dev1fonzz.fareezonzzmms",
    "sha256_cert_fingerprints": ["<SHA256 dari keystore signing kau>"]
  }
}]
```

Dapatkan SHA256 fingerprint: `keytool -list -v -keystore <keystore-kau>.jks`.
**Tanpa fail ni**, deep link tetap jalan tapi Android akan tunjuk dialog
"buka guna app / browser" dulu (bukan auto terus).

### 3. Laluan shortcut & deep link — sesuaikan ikut router sebenar kau

`app/src/main/res/xml/shortcuts.xml` guna placeholder laluan
(`/member/card`, `/support/tickets`, `/announcements`). Tukar ikut laluan
sebenar dalam React Router kau — bahagian lepas `fozmms://open` terus
jadi laluan yang dimuatkan (`Constants.BASE_URL + path`).

## Native Diagnostics Bridge — untuk modul SUPPORT_TICKET

App ni expose `window.AndroidDiagnostics` ke laman web (hanya wujud dalam
app, tak wujud di browser biasa — guna feature-detect). Reka bentuk
butang/UI/bila-nak-panggil semua kau tentukan di **web**, app cuma bekalkan
kebolehan native:

```js
if (window.AndroidDiagnostics) {
  // 1) Maklumat peranti — segera, TIADA permission diperlukan
  const info = JSON.parse(window.AndroidDiagnostics.getDeviceInfo())
  // { brand, manufacturer, model, androidVersion, sdkInt, appVersion,
  //   screenWidth, screenHeight, densityDpi, networkType, language,
  //   timezone, batteryPercent, freeStorageMB, totalStorageMB }

  // 2) Lokasi + alamat — trigger dialog permission native kali pertama
  window.onFozLocationResult = (r) => {
    // r = { granted, lat, lon, accuracy, address, reason }
    // reason muncul bila tiada lokasi: "permission_denied" |
    // "location_disabled" | "timeout"
  }
  window.AndroidDiagnostics.requestLocation()

  // 3) Salin ke papan keratan peranti (native clipboard)
  window.AndroidDiagnostics.copyToClipboard("teks diagnostik...")

  // 4) Dialog konfirmasi native + redirect ke channel sokongan
  window.AndroidDiagnostics.confirmAndOpen(
    "Hantar maklumat diagnostik ni ke sokongan?",
    "https://wa.me/60123456789?text=" + encodeURIComponent(info)
  )

  // 5) Imbas QR/barcode native (CardScanLogin, check-in event)
  window.onFozQrResult = (r) => {
    // r = { granted, content, format } atau { granted:false, reason:"cancelled" }
  }
  window.AndroidDiagnostics.scanQrCode()

  // 6) Kongsi teks/pautan guna share sheet Android
  window.AndroidDiagnostics.shareText("No. Ahli: JK-ID-1234", "Kad Keahlian FOZ MMS")

  // 7) Kunci biometric — suis dari Tetapan Ahli
  const supported = window.AndroidDiagnostics.isBiometricAvailable()
  window.AndroidDiagnostics.setBiometricLockEnabled(true) // atau false
} else {
  // Jalan dalam browser biasa — sorok butang ni / guna fallback web
}
```

**Nota privasi/keselamatan:**
- Lokasi **hanya** diminta bila web panggil `requestLocation()` — tiada
  pengesanan latar belakang/berterusan. Sekali bacaan, terus dibuang.
- Permission (Kamera & Lokasi) minta secara runtime, bukan waktu install.
- Bridge ni boleh dipanggil oleh **mana-mana** frame/iframe dalam WebView
  (had teknikal `addJavascriptInterface`). Sebab tu `ALLOWED_HOSTS` di
  `Constants.kt` kena kekal ketat — jangan embed iframe domain luar yang
  tak kau kawal dalam laman ahli.

## Cara import ke Android Studio

1. Buka **Android Studio** → `File` → `Open` → pilih folder `FareezOnzzMMS/`
   (folder yang ada `settings.gradle` di root).
2. Bila Android Studio sync kali pertama, ia akan minta buat **Gradle
   Wrapper** (sebab `gradle-wrapper.jar` binari tak disertakan dalam pakej
   ni). Klik **OK / Create Gradle Wrapper** bila digesa, atau:
   `File → Sync Project with Gradle Files` sekali lagi selepas tu.
3. Tunggu Gradle sync habis (kali pertama akan muat turun Gradle 8.7 +
   dependencies — perlukan internet).
4. Sambung telefon/emulator (Android 5.0+) → klik **Run ▶**.

## Tukar konfigurasi (kalau perlu)

Semua config app ada dalam **satu fail sahaja**, ikut CPSD:

```
app/src/main/java/com/dev1fonzz/fareezonzzmms/Constants.kt
```

- `BASE_URL` — tukar sini kalau kau pindah ke custom domain.
- `START_PATH` — default `/auth/login`. App terus bawa ahli ke skrin log
  masuk (bukan `/disclaimer` macam web version, sebab app ni khas untuk
  ahli sedia ada). Kalau ada sesi sah, web akan auto-redirect ke dashboard.
- `ALLOWED_HOSTS` — domain yang dibenarkan buka DALAM WebView. Domain lain
  auto terbuka di app luar (browser/WhatsApp/dsb). **Kena selaras** dengan
  `app/src/main/res/xml/network_security_config.xml` kalau kau tambah domain.

## Hasilkan APK / AAB untuk pasang di telefon ahli

- Debug APK cepat (untuk test): `Build → Build APK(s)`, fail keluar di
  `app/build/outputs/apk/debug/`.
- Untuk edar (Play Store / sideload rasmi): `Build → Generate Signed Bundle
  / APK`, buat keystore baru (simpan elok-elok — kena sama setiap kali
  update), pilih **release**.

## Auto-build APK guna GitHub Actions (tanpa Android Studio)

Fail `.github/workflows/android.yml` dah sedia — setiap kali kau `push` ke
branch `main`, GitHub automatik build APK untuk kau:

1. Push kod (dari Spck Editor / git command line / apa-apa cara)
2. Buka repo kau di GitHub → tab **Actions** → tengok build jalan (~3-8 minit
   kali pertama, lebih laju build seterusnya sebab Gradle cache)
3. Bila siap (✅ hijau) → klik build tu → scroll bawah **Artifacts** →
   download `FareezOnzzMMS-debug-apk` (fail zip mengandungi APK)

**Nota:** `local.properties` (nilai Google Sheets API) sengaja **tak**
disertakan dalam build CI ni (fail tu gitignored) — APK dari GitHub Actions
tetap jalan 100% normal, cuma ciri polling notification pengumuman senyap
tak aktif. Kalau nak APK dari CI ada ciri tu juga, boleh tambah nilai
sebagai **GitHub Secrets** — bagitahu kalau nak saya setup sekali.

## Struktur projek

```
FareezOnzzMMS/
├── app/
│   ├── build.gradle                 → dependencies & SDK config
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/.../MainActivity.kt → WebView engine (teras app)
│   │   ├── java/.../Constants.kt    → config (BASE_URL, dll)
│   │   ├── java/.../FozApplication.kt
│   │   └── res/                     → layout, warna, ikon, string
├── build.gradle / settings.gradle   → root Gradle config
└── gradle/wrapper/                  → Gradle wrapper config
```

## Nota

- Ikon app & splash logo dijana automatik ikut gradient jenama
  (`#C0392B → #E74C3C → #F39C12`) supaya konsisten dengan `foz-icon.svg`
  di web — tiada perlu edit lagi, tapi kau boleh ganti fail PNG dalam
  `res/mipmap-*` bila-bila kalau nak logo lain.
- Ikon offline guna vector SVG-native (ZENIS — tiada emoji).
- Kalau kau tambah domain/subdomain baru kat web nanti, ingat kemaskini
  `Constants.ALLOWED_HOSTS` **dan** `network_security_config.xml` sekali,
  kalau tak WebView akan block domain tu.
