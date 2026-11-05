package com.ng2.dawn

import android.app.Application
import android.os.Build

/** Runs once per process, before any activity: logging, crash capture, dev commands. */
class Ng2Application : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        CrashReporter.install(this)
        Telemetry.init(this)
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }
        DiagLog.stage("session start")
        DiagLog.i("session", "app=$v device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE}(${Build.VERSION.SDK_INT}) " +
            "abi=${Build.SUPPORTED_ABIS.firstOrNull()} debuggable=${DevCommands.isDebuggable(this)}")
        ExitReasons.logRecent(this)
        val loaded = NativeBridge.load()
        DiagLog.stage(if (loaded) "native library loaded" else "native library FAILED to load")
        if (loaded) DiagLog.i("session", "runtime_linked=${NativeBridge.nativeRuntimeLinked()}")
        DevCommands.register(this)
    }
}
