# Ninja's Dawn – Android

Port Android untuk proyek rekompilasi Ninja Gaiden II ("Ninja's Dawn", ReXGlue SDK).
**Baca `docs/ANDROID_PORT.md` dulu:** isinya status jujur, apa yang sudah
diverifikasi, dan langkah build mode runtime.

- `app/` — project Android Studio (Kotlin + NDK, arm64): setup, kontrol sentuh,
  gamepad, menu, editor layout.
- `sdk-patch/` — patch Android untuk ReXGlue SDK (`docs/SDK_ANDROID_PATCH.md`).
- `tools/` — `ng2dev.py` (PC: pasang, pantau FPS langsung, kirim input, laporan, analisis log), `apply_sdk_patch.sh`, `make_android_manifest.py`.
- `docs/DEBUGGING.md` — telemetri, log, perintah `adb`, dan cara membaca masalah.
- `tests/` — tes host (tanpa Android).

Coba cepat (mode uji, tanpa game/SDK), pilih salah satu:

- **Tanpa Android Studio:** unggah folder ini ke repo GitHub, buka tab *Actions*, jalankan
  `android-test-apk`, unduh APK dari *Artifacts*, pasang di HP (izinkan pemasangan dari
  sumber tak dikenal).
- **Android Studio:** buka folder ini (NDK 27, CMake 3.25) atau `./gradlew assembleDebug`.

Di HP: kartu *Cek perangkat* memeriksa GPU/CPU/RAM; tekan *Main* untuk mencoba kontrol.

Semua pemeriksaan sekaligus: `tests/run_all.sh` (yang tidak bisa dijalankan dilaporkan SKIP, bukan PASS).

Tes host:

    g++ -std=c++20 -Iapp/src/main/cpp tests/pad_state_test.cpp -o /tmp/t && /tmp/t
    python3 tests/xex_parse_check.py
    # logika Kotlin (butuh kotlinc, android.jar, org.json): lihat tests/run_kotlin_tests.sh
