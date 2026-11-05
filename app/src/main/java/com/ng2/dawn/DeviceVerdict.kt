package com.ng2.dawn

/**
 * Judges whether a phone can plausibly run the real game. Pure Kotlin (no
 * Android types) so it is unit-tested on the JVM; DeviceCheck gathers the facts.
 *
 * The Vulkan requirements are the ones the ReXGlue SDK enforces in
 * src/ui/vulkan/vulkan_device.cpp. Meeting them is necessary, not sufficient:
 * it says nothing about speed or driver bugs.
 */
enum class Level { OK, WARN, BLOCK }

data class Finding(val level: Level, val text: String)

data class VkDeviceInfo(
    val name: String,
    val type: String,
    val api: String,
    val features: Map<String, Boolean>,
    val swapchain: Boolean,
    val gfxCompute: Boolean,
    val localMb: Long,
)

data class VulkanReport(
    val loaded: Boolean,
    val error: String?,
    val instance: String,
    val devices: List<VkDeviceInfo>,
)

data class DeviceInfo(
    val sdkInt: Int,
    val abis: List<String>,
    val totalRamMb: Long,
    val freeStorageMb: Long,
    val cpuParts: List<Int>,       // /proc/cpuinfo "CPU part" values, one per core
    val vulkan: VulkanReport?,
)

object VulkanReportParser {
    /** Parses the "key=value" text produced by ng2_gpu_probe.cpp. Never throws. */
    fun parse(text: String): VulkanReport {
        val kv = HashMap<String, String>()
        for (line in text.lineSequence()) {
            val i = line.indexOf('=')
            if (i > 0) kv[line.substring(0, i)] = line.substring(i + 1).trim()
        }
        if (kv["loaded"] != "1") {
            return VulkanReport(false, kv["error"] ?: "tidak diketahui", "", emptyList())
        }
        val n = kv["devices"]?.toIntOrNull() ?: 0
        val devices = (0 until n).map { d ->
            val p = "d$d."
            val fp = "${p}f."
            VkDeviceInfo(
                name = kv["${p}name"] ?: "?",
                type = kv["${p}type"] ?: "?",
                api = kv["${p}api"] ?: "?",
                features = kv.filterKeys { it.startsWith(fp) }
                    .map { it.key.removePrefix(fp) to (it.value == "1") }.toMap(),
                swapchain = kv["${p}swapchain"] == "1",
                gfxCompute = kv["${p}gfxcompute"] == "1",
                localMb = kv["${p}localmb"]?.toLongOrNull() ?: 0L,
            )
        }
        return VulkanReport(true, null, kv["instance"] ?: "?", devices)
    }
}

object DeviceVerdict {
    /** Hard requirements of the SDK's Vulkan device selection (always enforced). */
    val REQUIRED_FEATURES = listOf("independentBlend", "fragmentStoresAndAtomics", "vertexPipelineStoresAndAtomics")
    /** Enforced by default, but the SDK exposes cvars to relax them (the app does). */
    val RELAXABLE_FEATURES = listOf("geometryShader", "fillModeNonSolid")

    private val LITTLE_CORES = setOf(0xd01, 0xd03, 0xd04, 0xd05)   // A32, A53, A35, A55

    fun cpuName(part: Int): String = when (part) {
        0xd01 -> "Cortex-A32"; 0xd03 -> "Cortex-A53"; 0xd04 -> "Cortex-A35"; 0xd05 -> "Cortex-A55"
        0xd07 -> "Cortex-A57"; 0xd08 -> "Cortex-A72"; 0xd09 -> "Cortex-A73"; 0xd0a -> "Cortex-A75"
        0xd0b -> "Cortex-A76"; 0xd0d -> "Cortex-A77"; 0xd41 -> "Cortex-A78"; 0xd44 -> "Cortex-X1"
        else -> "0x" + part.toString(16)
    }

    private fun meetsHard(d: VkDeviceInfo) =
        d.swapchain && d.gfxCompute && REQUIRED_FEATURES.all { d.features[it] == true }

    fun evaluate(i: DeviceInfo): List<Finding> {
        val out = ArrayList<Finding>()

        if (i.abis.none { it == "arm64-v8a" })
            out += Finding(Level.BLOCK, "CPU bukan arm64-v8a (${i.abis.joinToString()}). Game butuh 64-bit.")
        if (i.sdkInt < 29)
            out += Finding(Level.BLOCK, "Android ${i.sdkInt} terlalu lama; minimal Android 10 (API 29).")

        // ---- Vulkan ----
        val vk = i.vulkan
        if (vk == null || !vk.loaded) {
            out += Finding(Level.BLOCK, "Vulkan tidak tersedia: ${vk?.error ?: "tidak diperiksa"}.")
        } else if (vk.devices.isEmpty()) {
            out += Finding(Level.BLOCK, "Vulkan termuat tetapi tidak ada GPU yang dilaporkan.")
        } else {
            val dev = vk.devices.firstOrNull { meetsHard(it) } ?: vk.devices.first()
            val name = "${dev.name} (Vulkan ${dev.api})"
            val missing = REQUIRED_FEATURES.filter { dev.features[it] != true }
            if (!dev.swapchain) out += Finding(Level.BLOCK, "$name: tidak punya VK_KHR_swapchain.")
            if (!dev.gfxCompute) out += Finding(Level.BLOCK, "$name: tidak ada antrean graphics+compute.")
            if (missing.isNotEmpty())
                out += Finding(Level.BLOCK,
                    "$name: fitur wajib SDK tidak ada: ${missing.joinToString()}. Game tidak bisa dijalankan di GPU ini.")
            val relaxed = RELAXABLE_FEATURES.filter { dev.features[it] != true }
            if (relaxed.isNotEmpty())
                out += Finding(Level.WARN,
                    "$name: tanpa ${relaxed.joinToString()}. Aplikasi mematikan syarat itu agar GPU tetap dipilih; " +
                    "SDK punya jalur cadangan tetapi belum diuji.")
            if (!dev.api.startsWith("1.0") || dev.api == "?") {
                // 1.1+ : fine
            } else {
                out += Finding(Level.WARN, "$name hanya Vulkan 1.0; SDK menerimanya tetapi belum diuji pada versi ini.")
            }
            if (missing.isEmpty() && dev.swapchain && dev.gfxCompute)
                out += Finding(Level.OK, "$name memenuhi syarat dasar SDK. Ini belum menjamin game berjalan atau cukup cepat.")
        }

        // ---- RAM / CPU / storage ----
        if (i.totalRamMb in 1..5999)
            out += Finding(Level.WARN,
                "RAM ${i.totalRamMb} MB. Heap guest 512 MB ditambah cache GPU dan Android sendiri sangat ketat; " +
                "sistem bisa menutup game.")
        else if (i.totalRamMb >= 6000)
            out += Finding(Level.OK, "RAM ${i.totalRamMb} MB.")

        if (i.cpuParts.isNotEmpty()) {
            val counts = i.cpuParts.groupingBy { it }.eachCount().entries.joinToString(", ") { "${it.value}× ${cpuName(it.key)}" }
            if (i.cpuParts.all { it in LITTLE_CORES })
                out += Finding(Level.WARN,
                    "CPU: $counts. Hanya inti kecil/in-order; kode hasil recompile berat, " +
                    "kecil kemungkinan mencapai 30 fps.")
            else out += Finding(Level.OK, "CPU: $counts.")
        }

        if (i.freeStorageMb in 0..10239)
            out += Finding(Level.WARN, "Ruang kosong ${i.freeStorageMb} MB. Salinan game butuh beberapa GB, dan perlu ruang kerja.")
        return out
    }

    /** Worst level present; OK if nothing was found. */
    fun overall(f: List<Finding>): Level =
        f.maxByOrNull { it.level.ordinal }?.level ?: Level.OK
}
