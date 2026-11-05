package com.ng2.dawn

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import java.io.File

/** Collects the facts DeviceVerdict judges. Safe to call from a background thread. */
object DeviceCheck {

    fun gather(context: Context): DeviceInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val free = try { StatFs(base.absolutePath).availableBytes shr 20 } catch (_: Exception) { -1L }

        val vk = if (NativeBridge.load()) VulkanReportParser.parse(NativeBridge.nativeProbeVulkan())
                 else VulkanReport(false, "library native tidak termuat", "", emptyList())

        return DeviceInfo(
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.toList(),
            totalRamMb = mem.totalMem shr 20,
            freeStorageMb = free,
            cpuParts = cpuParts(),
            vulkan = vk,
        )
    }

    /** "CPU part : 0xd03" lines of /proc/cpuinfo, one per core. Empty if unreadable. */
    fun cpuParts(): List<Int> = try {
        parseCpuParts(File("/proc/cpuinfo").readText())
    } catch (_: Exception) { emptyList() }

    fun parseCpuParts(text: String): List<Int> =
        text.lineSequence()
            .filter { it.startsWith("CPU part") }
            .mapNotNull { it.substringAfter(':').trim().removePrefix("0x").toIntOrNull(16) }
            .toList()
}
