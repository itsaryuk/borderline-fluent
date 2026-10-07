package com.lampensoft.borderlinefluent

import android.app.ActivityManager
import android.content.Context
import android.os.Build

/** Can this phone run the game at all (CPU, RAM)? Whether it has Gemini Nano is the probe in [Game]. */
object Device {
    // ponytail: 7 GB total covers "8 GB" phones (they report ~7.3-7.6). Lower this only after testing one.
    private const val MIN_RAM_GB = 7.0

    /** Null if supported, otherwise the reason to show the player. */
    fun check(ctx: Context): String? {
        if ("arm64-v8a" !in Build.SUPPORTED_ABIS) return ctx.getString(R.string.unsupported_arch, ctx.getString(R.string.app_name))
        val mem = ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        val gb = mem.totalMem / 1e9
        if (gb < MIN_RAM_GB) return ctx.getString(R.string.unsupported_ram, ctx.getString(R.string.app_name), gb)
        return null
    }
}
