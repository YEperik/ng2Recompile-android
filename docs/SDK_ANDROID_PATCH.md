# Patch Android untuk ReXGlue SDK

`sdk-patch/rexglue-android.patch` ditulis terhadap snapshot SDK yang Anda unggah
(`project(ReXGlue VERSION 0.10.0)`). Terapkan dengan:

    tools/apply_sdk_patch.sh /path/ke/rexglue-sdk

Skrip itu menjalankan `patch --dry-run` dulu dan menolak jika SDK Anda berbeda.
**Diuji:** patch bisa diterapkan bersih ke salinan SDK, `CMakePresets.json` tetap
JSON valid, dan dua file C++ baru lolos pemeriksaan sintaks terhadap header NDK
tiruan. **Belum diuji:** kompilasi sebenarnya dengan NDK, karena submodule
`thirdparty/` di ZIP kosong dan tidak ada NDK di lingkungan ini.

## Apa yang sudah ada di SDK (tidak perlu ditulis)

SDK ini lebih siap dari dugaan awal. Sudah ada: backend Vulkan lengkap
(`src/graphics/vulkan`), jendela via SDL3, SIMDe untuk instruksi vektor, jalur
`REX_ARCH_ARM64` (exception handler, mmio handler, FPSCR, intrinsics), preset dan
CI `linux-arm64`, nama library Android (`libvulkan.so`) di `platform/dynlib.h`,
ekstensi `VK_KHR_android_surface`, pembuatan swapchain Android di
`vulkan_presenter.cpp`, dan potongan Android di `memory_posix.cpp` /
`threading_posix.cpp` (warisan Xenia). `platform.h` sudah mengenali `__ANDROID__`.

## Yang hilang, dan perbaikannya

| # | Masalah (bukti) | Perbaikan |
|---|---|---|
| 1 | `rex/main_android.h` di-`#include` oleh `threading_posix.cpp` tetapi tidak ada; `memory_posix.cpp` memanggil `GetAndroidApiLevel()` dengan include yang di-comment ("Android or maybe na. idk") | header + `src/core/main_android.cpp`; include diaktifkan |
| 2 | `rex/ui/surface_android.h` di-include `vulkan_presenter.cpp` tetapi tidak ada | `AndroidNativeWindowSurface` (+ `.cpp`) |
| 3 | `WindowSDL::CreateSurfaceImpl` hanya punya cabang Win32/Mac/Wayland/X11; blok `#else` memuat `<X11/Xlib-xcb.h>` | cabang Android dari `SDL_PROP_WINDOW_ANDROID_WINDOW_POINTER` (properti ini ada di SDL 3.4) |
| 4 | Di Android `XE_UI_WINDOWED_APPS_IN_LIBRARY=1`, yang membuat `GetWindowedAppCreator()` tidak terdefinisi; `windowed_app_main_sdl.cpp` memanggilnya, jadi tidak akan link | makro itu dihapus untuk Android (satu app per library, seperti platform lain) |
| 5 | Titik masuk memakai `main()` biasa; SDLActivity memanggil `SDL_main` | `#include <SDL3/SDL_main.h>` khusus Android |
| 6 | `thread::AndroidInitialize()` dan `memory::AndroidInitialize()` ada tetapi tidak pernah dipanggil | dipanggil/dimatikan di `RunWindowedApp` |
| 7 | `OpenAndroidContentFileDescriptor` dideklarasikan dan dipakai `mapped_memory_posix.cpp` tetapi tidak didefinisikan, jadi error link | stub yang mengembalikan -1 (app menyalin game ke path biasa) |
| 8 | CMake root: `ANDROID` dianggap `UNIX` lalu jatuh ke cabang "linux" | cabang `android-arm64` (hanya arm64) |
| 9 | `src/ui/CMakeLists.txt` mewajibkan `x11-xcb` dan `wayland-client` via pkg-config | Android memakai `surface_android.cpp`, tanpa pkg-config |
| 10 | `thirdparty/CMakeLists.txt` memaksa SDL X11/Wayland/ALSA/Pulse/PipeWire | dilewati untuk Android |
| 11 | `codegen` dan CLI `rexglue` selalu ikut dibuild, padahal tidak bisa jalan di ponsel | opsi `REXGLUE_BUILD_TOOLS` (default ON; preset Android OFF) |
| 12 | Tidak ada preset Android | preset `android-arm64` (NDK r27+, API 29) |

Tidak diubah: tidak ada perubahan pada logika emulasi, GPU, kernel, atau codegen.

## Yang sengaja tidak ditangani

- `rex::filesystem::AndroidInitialize` dan `rex::InitializeAndroidSystemForApplicationContext`
  dideklarasikan tetapi tidak punya implementasi; tidak dipanggil siapa pun.
- Pembukaan dokumen `content://` langsung (lihat #7).
- Siklus hidup surface Android (lihat risiko di `ANDROID_PORT.md`).
