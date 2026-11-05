# Debugging, telemetri, dan log

Semua alat di sini membantu menjawab satu pertanyaan: **kalau game tidak jalan, berhenti di tahap mana dan kenapa.**
Contoh keluaran di bawah dibuat langsung oleh alatnya dari **log buatan** (bukan dari HP sungguhan).

## Alur cepat (dari PC)

Butuh `adb` (Android platform-tools) dan Python 3.8+. Di HP: aktifkan *Opsi pengembang* (Setelan > Tentang ponsel >
ketuk Nomor build 7×) lalu *USB debugging*. Android 11 juga punya *Wireless debugging* (`adb pair`, `adb connect`).
Jika `adb install` tertahan, lihat layar HP: sebagian vendor meminta konfirmasi di sana.

```
python tools/ng2dev.py install           # pasang APK debug terbaru
python tools/ng2dev.py doctor            # adb, Android, GPU, kesiapan: semua dalam satu laporan
python tools/ng2dev.py play              # buka layar game
python tools/ng2dev.py watch             # FPS, frame time, RAM, suhu: satu baris per detik
python tools/ng2dev.py pad "A+X lx=0.5 for=300"   # tekan tombol dari PC (tanpa menyentuh HP)
python tools/ng2dev.py report            # buat laporan di HP, tarik ke PC, analisis otomatis
```

Perintah `pad`, `fps`, `report`, `doctor`, `cmd` hanya bekerja pada **build debug** (penerimanya tidak didaftarkan di
build release). `watch`, `logs`, `analyze` bekerja pada build apa pun.

| Perintah | Fungsi |
|---|---|
| `doctor` | cek adb, model/Android/ABI, fitur Vulkan, app terpasang, perintah app, kesiapan, probe GPU langsung |
| `install [APK]` / `run` / `play` / `stop` | pasang, buka setup, buka game (otomatis memilih layar uji/runtime), hentikan |
| `watch [--udp PORT] [--csv F] [--seconds N] [--target 30]` | pantau langsung; `--csv` menyimpan; `--udp` lewat Wi-Fi tanpa adb |
| `pad "<spec>"` / `pad release` | injeksi kontroler. Tombol: A B X Y LB RB START BACK L3 R3 UP DOWN LEFT RIGHT. Sumbu: `lx ly rx ry` (−1..1), `lt rt` (0..1), `for=ms` (maks 60000) |
| `fps on\|off` | tampilan FPS di HP |
| `cmd "<perintah>"` | ping, pad, fps, stats, preflight, probe, report, loglevel, state |
| `report [--out DIR]` | laporan diagnosa lengkap + analisis |
| `logs [--errors] [--save F]` | logcat tersaring (app, runtime game, crash) |
| `analyze FILE\|-` | jelaskan laporan atau dump logcat; kode keluar 1 jika ada galat |

### Mode Wi-Fi ("kirim data secara instan")

Di HP: layar setup > *Diagnostik* > isi `IP-PC:47321`. Di PC: `python tools/ng2dev.py watch --udp 47321`
(alat menampilkan IP PC Anda). Telemetri dikirim satu paket UDP JSON per detik; tanpa kabel dan tanpa adb. Tanpa
diisi, data tetap keluar lewat logcat (tag `ng2.stats`) yang dibaca `watch` biasa.

## Contoh: `watch`

```
22:13:20  menunggu frame… (4s) | disp 60.0 | RAM 800MB (bebas 500) | NONE 35.0°C | polls 1800 | pad -
22:13:21  fps  29.8  frame  33.3ms p95  40.0 max   60.0  hitch 0 | disp 60.0 | RAM 800MB (bebas 500) | NONE 35.0°C | polls 210 | pad A
22:13:22  fps  19.4  frame  33.3ms p95  71.0 max  190.0  hitch 3 | disp 60.0 | RAM 800MB (bebas 500) | MODERATE 41.2°C | polls 1800 | pad -
22:13:23  TIDAK ADA FRAME 5s | disp 60.0 | RAM 800MB (bebas 500) | NONE 35.0°C | polls 1800 | pad -
```
Baris 1: game belum menghasilkan frame. Baris 2: sehat (30 FPS, tombol A ditekan, game sudah membaca kontroler 210×).
Baris 3: lambat, tersendat, dan HP mulai panas. Baris 4: game macet. `Ctrl-C` menampilkan ringkasan:

```
4 sampel
fps min 19.0  median 30.0  maks 30.0  rata-rata 27.2; 25% detik di bawah target 30
frame p95 terburuk 40.0 ms; tersendat 0 kali
RAM maks 800 MB; suhu baterai maks 35.0°C

```

**Arti FPS.** `fps` adalah *iterasi loop utama game* (hook per-frame di alamat guest `0x822F3A8C`, titik yang sama
dengan pengukur FPS di versi desktop), bukan jumlah gambar yang ditampilkan layar. `disp` adalah refresh layar
(Choreographer); game 15 FPS tetap menampilkan `disp 60`. Di **mode uji**, "frame" adalah frame monitor kontroler.
Di build runtime hook ini hanya ada jika codegen memakai manifest dari `tools/make_android_manifest.py`.

## Contoh: `analyze`

```
Tahap startup
  ✔ proses dimulai
  ✔ library native termuat
  ✔ tombol Main ditekan
  ✔ layar game dibuat
  ✔ SDL siap (jendela)
  ✔ loader Vulkan ditemukan
  ✔ instance Vulkan dibuat
  ✖ GPU dipilih   <- berhenti di sini
  · frame game pertama
  · game membaca kontroler

Kemungkinan penyebab: Tidak ada GPU yang memenuhi syarat SDK (fitur wajib: independentBlend, fragmentStoresAndAtomics, vertexPipelineStoresAndAtomics, VK_KHR_swapchain, antrean graphics+compute). Jalankan `ng2dev.py cmd probe`.

[GALAT] Tidak ada GPU yang lolos syarat SDK
        Instance Vulkan terbentuk, tetapi tidak ada baris 'Vulkan device properties and enabled features'.
        -> GPU tidak punya fitur wajib (SDK menolaknya tanpa pesan). Jalankan `ng2dev.py cmd probe` dan lihat d0.f.*.
```

Tahap yang dilacak, dan dari mana penandanya berasal:

| Tahap | Penanda | Sumber |
|---|---|---|
| proses dimulai, library native termuat, tombol Main, layar game | `stage: ...` | log app (`DiagLog.stage`) |
| SDL siap | `SDL video driver:` | log SDK |
| loader / instance Vulkan | `Loaded Vulkan runtime from`, `Vulkan instance API version` | log SDK (`vulkan_instance.cpp`) |
| GPU dipilih | `Vulkan device properties and enabled features` | log SDK (`vulkan_device.cpp`) |
| frame game pertama | telemetri `frames > 0` | hook frame |
| game membaca kontroler | `stage: guest polled input for the first time` | driver input |

Saat SDK menolak sebuah GPU ia menulis peringatan bernada `Vulkan device 'X' doesn't support <fitur> ...`
(`vulkan_device.cpp`); penganalisis mengenali tiap pesannya dan menyebut fitur yang kurang. Jika instance Vulkan
terbentuk tetapi tidak ada baris GPU **dan** tidak ada peringatan itu, log kemungkinan tidak lengkap (naikkan detail log);
`cmd probe` menunjukkan fitur GPU secara langsung.

## Di mana log berada

| Apa | Lokasi | Isi |
|---|---|---|
| Log app | `files/logs/ng2-app.log` (+ `.1`) | tahap, preflight, perintah, telemetri tiap 5 detik; berputar di 256 KB |
| Crash Kotlin | `files/logs/crash-<waktu>.txt` | stack + 80 baris log terakhir |
| Log SDK/game | `files/logs/rex.log` dan logcat tag `rex` | patch SDK menambahkan sink logcat; tanpa itu log SDK hilang di Android |
| Laporan | `/sdcard/Android/data/com.ng2.dawn/files/reports/` | satu berkas teks, 10 terbaru |
| Telemetri | logcat tag `ng2.stats` (+ UDP opsional) | satu JSON per detik |
| Jawaban perintah | logcat tag `ng2.cmd` | satu JSON per perintah |

Laporan memuat: perangkat/SoC/RAM/inti CPU, probe Vulkan mentah, penilaian perangkat, kesiapan (dengan solusi),
**alasan proses sebelumnya berhenti** (Android 11: crash native, dimatikan karena memori, ANR, dihentikan sinyal),
pengaturan, 60 detik telemetri, crash Kotlin, 800 baris log app, dan logcat app ini.
Crash native tidak bisa ditangkap oleh Kotlin; terlihat sebagai alasan "CRASH_NATIVE" pada peluncuran berikutnya, dan
`logs`/`analyze` menunjuk modul pada frame teratas (driver GPU atau `libmain.so`).

Detail log SDK: setup > *Detail log game* (info/debug/trace), atau `cmd "loglevel debug"`; berlaku saat game dimulai ulang.

## Kontrol

- **Pemetaan ulang:** ☰ > *Atur posisi / fungsi tombol* > ketuk tombol bulat > *Fungsi* > pilih A/B/X/Y/LB/RB/Start/Back/L3/R3/arah d-pad.
  Posisi, ukuran, dan fungsi tersimpan.
- **Stik:** *Tampilan & stik* memuat zona mati (0–0,4), sensitivitas (0,5–2×), transparansi, ukuran, getar, stik melayang.
- **Uji tanpa menyentuh HP:** `pad "A for=200"`. Injeksi bekerja bersama sentuhan dan gamepad; stik terkuat menang.

## Yang belum terbukti

- Format baris SDK diambil dari **membaca kode sumbernya**, bukan dari menjalankannya di perangkat; penanda tahap SDK
  bisa meleset jika SDK mengubah teksnya.
- Penganalisis crash native diuji dengan potongan tombstone buatan; format asli di Realme UI belum dilihat.
- `ng2dev.py` diuji dengan `adb` palsu (`tests/fake_adb.py`), bukan HP sungguhan. Penerima `am broadcast` pada Android 11
  belum diuji pada perangkat.
- Pengukuran FPS runtime butuh hook hasil codegen; belum ada game yang dijalankan.
