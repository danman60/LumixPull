package com.lumixpull.app

import android.app.Application
import android.content.Context

class LumixPullApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Save crash trace to shared prefs so we can show it on next launch
            try {
                val prefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                val trace = buildString {
                    appendLine("Crash at ${System.currentTimeMillis()}")
                    appendLine("Thread: ${thread.name}")
                    appendLine(throwable.stackTraceToString())
                }
                prefs.edit().putString("last_crash", trace).apply()
            } catch (_: Exception) {}

            // Call default handler to let the system handle the crash
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun getLastCrash(context: Context): String? {
            val prefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
            return prefs.getString("last_crash", null)
        }

        fun clearCrash(context: Context) {
            context.getSharedPreferences("crash_log", Context.MODE_PRIVATE).edit().remove("last_crash").apply()
        }
    }
}
