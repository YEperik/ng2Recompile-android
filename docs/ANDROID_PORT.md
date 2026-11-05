# Ninja's Dawn → Android: status, arsitektur, dan langkah build

## Status singkat

Ini project Android lengkap (Kotlin + NDK) plus patch Android untuk ReXGlue SDK.
Semua bagian yang bisa ditulis tanpa perangkat dan NDK sudah ditulis. **Belum ada
yang dijalankan di Android**, dan game belum pernah diuji berjalan di ponsel.

| Sudah diverifikasi di lingkungan ini | Belum diverifikasi |
|---|---|
| Seluruh Kotlin dikompilasi dengan kompiler Kotlin 1.9.24 terhadap `android.jar` API 34 asli (androidx hanya berupa stub buatan saya) | Gradle sync dan APK sungguhan (Google Maven tidak terjangkau dari sini) |
| Java SDL3 yang disertakan dikompilasi `javac` terhadap API 34 (butuh compileSdk ≥ 31; proyek memakai 34) | Kompilasi C++ dengan NDK sungguhan |
| 500+ pengecekan JVM: matematika kontrol, parser XEX/Vulkan, penilaian perangkat, `ControlLayout` + pemetaan ulang (dengan `org.json` asli), perintah `pad`, format statistik, overlay FPS, daftar periksa, alasan proses berhenti | Jalan di perangkat, rasa kontrol sentuh |
| Probe Vulkan native dijalankan melawan implementasi Vulkan sungguhan (Mesa lavapipe): jalur sukses dan jalur "tanpa Vulkan" | Fitur Vulkan GPU PowerVR GE8320 di HP Anda (probe akan menjawabnya di perangkat) |
| 17/17 fungsi JNI cocok tipe parameternya antara Kotlin dan C++ | Apakah backend Vulkan SDK jalan di driver seluler |
| Patch SDK bisa diterapkan bersih; 8 file SDK yang dipatch/terkait dikompilasi dengan `__ANDROID__` memakai header SDL3 dan Vulkan asli; simbol `SDL_main` terbukti diekspor tanpa name-mangling (`nm`) | `ng2_app_android.h`, `ng2_android_pad_driver.cpp`, `ng2_iso_android.cpp` (mode SDK) |
| CMake mode uji dikonfigurasi dan seluruh objeknya dikompilasi; pesan galat mode runtime diuji | Siklus hidup surface saat app ke latar belakang |
| `ng2dev.py` (30 tes dengan `adb` palsu, termasuk UDP, CSV, kutip shell, analisis log) | `ng2dev.py` dan perintah dev pada HP sungguhan |
| Statistik FPS native (30/60 fps, hitch, macet, wrap ring buffer) diuji dengan jam buatan | Hook frame pada game hasil codegen |

Harapkan perbaikan kecil pada sync/build pertama.

## Temuan penting dari SDK

SDK ternyata sudah mendukung hampir semua yang saya kira belum ada: backend Vulkan,
SDL3, SIMDe (vektor VMX dijalankan lewat NEON), jalur ARM64 (exception handler,
mmio, FPSCR), dan CI `linux-arm64`. Yang kurang adalah lapisan Android itu sendiri
(header yang dirujuk tetapi tidak ada, cabang surface, entry point, CMake). Itu
sudah ditambal di `sdk-patch/` (rincian: `SDK_ANDROID_PATCH.md`).

Jawaban atas pertanyaan terbuka sebelumnya:

1. **GPU:** ada backend Vulkan di `src/graphics/vulkan`, bukan hanya D3D12. Belum
   diketahui apakah berjalan baik di GPU seluler (Adreno/Mali).
2. **CPU:** kode vektor memakai SIMDe dengan cabang `__aarch64__`; kode hasil generate
   dapat dikompilasi untuk ARM64. Kecepatan di ponsel belum diketahui.
3. **Memori:** reservasi ruang alamat guest 4 GB (+4 GB alias fisik) bersifat
   *virtual*; heap fisik guest hanya 512 MB (`0x20000000`). Beban nyata ada di cache
   tekstur/shader GPU. Belum diuji pada kernel Android (batas overcommit/RLIMIT_AS).
4. **Input:** driver sintetis memang pola resmi di repo asli (`AutoSkipDriver`);
   `ng2_android_pad_driver.cpp` mengikutinya. Flag `synthetic=false` perlu dicek.
5. **Kinerja:** target realistis 30 fps di perangkat unggulan; default setup 30.

## HP Anda: Realme C21, Android 11

Spesifikasi (GSMArena dan beberapa situs lain): MediaTek Helio G35, 8 inti
Cortex-A53 (4×2,3 GHz + 4×1,8 GHz), GPU PowerVR GE8320, RAM 3 atau 4 GB, penyimpanan
32 atau 64 GB, dirilis dengan Android 10 (Android 11 sudah memenuhi `minSdk 29`).

- **Mode uji: harus bisa dipasang dan dipakai.** arm64, API 30. Cocok untuk menyetel
  kontrol sentuh.
- **Game sungguhan: sangat kecil kemungkinan berjalan pada kecepatan layak.** Semua
  inti adalah Cortex-A53 (in-order, kecil), RAM sempit (heap guest 512 MB + cache GPU +
  Android), dan GPU entry-level. Ini penilaian, bukan hasil uji.
- **Satu hal yang tidak saya tahu dan bisa dijawab HP Anda sendiri:** apakah driver
  Vulkan GE8320 menyediakan fitur yang diwajibkan SDK (`independentBlend`,
  `fragmentStoresAndAtomics`, `vertexPipelineStoresAndAtomics`). Sumber sekunder
  menyebut GE8320 mendukung Vulkan 1.1, tetapi tidak menyebut fitur. Kartu **Cek
  perangkat** di layar setup menjalankan probe langsung ke driver HP dan menampilkan
  hasilnya, termasuk CPU dan RAM.
- **Khusus Android 11:** picker folder tidak bisa memilih folder utama penyimpanan,
  folder `Download` itu sendiri, atau `Android/data` (dokumentasi Android). Layar setup
  menampilkan petunjuknya: pindahkan game ke subfolder (mis. `Download/NG2`). Swipe
  tepi layar untuk "Back" (navigasi gestur) dicegah menimpa tombol di tepi lewat
  `systemGestureExclusionRects`. Belum diperiksa: posisi tombol terhadap notch
  waterdrop dan penghemat baterai Realme yang bisa mematikan app di latar belakang.

## Bug yang ditemukan dan diperbaiki oleh pemeriksaan ini

| Bug | Ditemukan oleh | Perbaikan |
|---|---|---|
| `parseXex`: offset sangat besar membuat `off + 24` overflow Int, melewati cek batas, crash pada `default.xex` rusak | tes JVM | aritmetika Long |
| Berkas sumber kosong tidak pernah dibuat saat menyalin game (`length()==0` pada berkas yang belum ada) | tinjauan kode | `dst.isFile &&` |
| Pesan galat impor langsung tertimpa oleh `refresh()` | tinjauan kode | TextView `notice` terpisah |
| Stik kiri dan d-pad bertumpuk pada layar 4:3 | tes tata letak | d-pad digeser; diuji pada 4 rasio layar |
| `ng2_gpu_probe.cpp` kurang `<cstdarg>` | kompilasi | include ditambah |
| `-0.0f` pada keluaran stik membuat state tidak pernah "sama" | tes JVM | dinormalkan |
| Syarat `geometryShader`/`fillModeNonSolid` SDK menolak banyak GPU seluler | pembacaan `vulkan_device.cpp` | env `REX_VULKAN_REQUIRE_*=false` (SDK punya jalur cadangan; belum diuji) |
| `uses-feature` Vulkan `required=true` menyaring perangkat sebelum bisa dites | tinjauan | `required=false`; app memeriksa sendiri |

## Dua mode build

**Mode uji (default).** `./gradlew assembleDebug`. Tanpa SDK dan game. App
terpasang penuh: layar setup, penyalinan folder game, kontrol sentuh, gamepad, menu,
editor layout. "Game"-nya monitor kontroler yang menyala saat tombol/stik disentuh.

**Mode runtime.** Library bernama `libmain.so` (syarat SDLActivity) berisi: SDK +
game hasil recompile + SDL3 + kode di `cpp/`. `GameSdlActivity` (turunan `SDLActivity`
dari SDL 3.4, Java-nya disertakan di `org/libsdl/app`) memiliki surface dan siklus
hidup, lalu menjalankan `SDL_main` milik SDK. Kontrol sentuh dari Kotlin masuk lewat
JNI ke `PadMixer`, dibaca `AndroidPadDriver`, dan sampai ke guest sebagai pad 0.
Gamepad fisik ditangani SDL dan driver SDL milik SDK.

## Langkah mode runtime

Butuh: PC (Windows/Linux) dengan Clang 18+, Android NDK **r27+**, CMake 3.25+,
ruang disk lega. Output codegen sekitar **563 MB C++ dalam ±1.100 file**; build
Android-nya berat dan dilakukan di PC, bukan di ponsel.

1. **Ambil SDK lengkap**, bukan ZIP-nya (submodule di ZIP kosong):
   `git clone --recursive https://github.com/rexglue/rexglue-sdk`
   lalu `tools/apply_sdk_patch.sh <sdk>`.
2. **Codegen di PC** memakai build *host* SDK dan `default.xex` milik Anda:
   `python tools/make_android_manifest.py <repo>/ng2_manifest.toml` (membuang
   `diagnostics.toml`, hook Win32), lalu `rexglue codegen` dengan manifest itu.
   Jalankan juga dua skrip perbaikan desktop (`patch_missed_regs.py`,
   `patch_scanguard.py`) pada `generated/default` sebelum build Android. Build Android
   tidak menjalankan codegen.
3. **Build:**
   ```
   ./gradlew assembleRelease \
     -Png2.withRexglue=ON -Png2.repoDir=/path/Ninja-s-Dawn -Png2.sdkDir=/path/rexglue-sdk
   ```
4. Pasang, pilih folder game (hasil ekstrak disc, berisi `default.xex`) di layar
   setup, tekan *Main*.

## Risiko: status

Selesai diperiksa: **config FFmpeg.** `config.h` fork FFmpeg memilih berkas lewat
`__gnu_linux__` sebelum `__ANDROID__`; clang untuk Android tidak mendefinisikan
`__gnu_linux__` (dicek dengan clang 22 target `aarch64-linux-android`), jadi
`config_android_aarch64.h` terpilih.

Masih terbuka (urut dari yang paling mungkin menggagalkan):

1. **Driver Vulkan seluler.** Emulasi Xenos (EDRAM, resolve, format tekstur) sering
   bermasalah di GPU tile-based. Fitur wajib bisa dicek (kartu Cek perangkat); perilaku
   nyata baru diketahui saat dijalankan.
2. **Siklus hidup surface.** Saat app ke latar belakang SDL menghancurkan
   `ANativeWindow`; presenter harus membuat ulang swapchain. Belum dicek.
3. **Properti window SDL.** Patch mengandalkan `SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER`
   terisi untuk jendela tanpa flag Vulkan/GL. Perlu dikonfirmasi di perangkat.
4. **Nama target SDL statis** di CMake (`SDL3-static` vs `SDL3::SDL3-static`);
   dicoba keduanya dengan peringatan jika tidak ketemu.
5. **Ukuran halaman 16 KB** pada perangkat baru (SDK memakai `getpagesize()`).
6. **Waktu kompilasi** Release dengan 563 MB kode generate.
7. **Gaya kontrol** NG2 (kombo cepat) di layar sentuh perlu disetel di perangkat.

## Kontrol

Pad Xbox 360 penuh: dua stik (+ L3/R3), d-pad 8 arah, A/B/X/Y, LB/RB, LT/RT,
Start/Back. Banyak jari sekaligus; geser jempol antar tombol untuk kombo; stik kiri
bisa melayang. Menu: tombol ☰, tombol Back, atau tombol Guide pada gamepad. Dari
menu: ubah posisi/ukuran tiap kontrol, transparansi, getar, sembunyikan overlay.
Overlay otomatis tersembunyi saat gamepad dipakai (bisa dimatikan). Di mode runtime
menu tidak menjeda game (guest tidak bisa disuspend dari sini).

## Hukum

Tidak ada data game yang disertakan atau diunduh; pemain memakai salinan sah miliknya.
Java SDL3 disertakan di bawah lisensi zlib (`org/libsdl/app/LICENSE-SDL.txt`).
