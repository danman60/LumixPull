package com.lumixpull.app

import android.content.Context

class TransferPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("lumixpull", Context.MODE_PRIVATE)

    var lastTransferTimestamp: Long
        get() = prefs.getLong("last_transfer_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_transfer_timestamp", value).apply()

    var totalTransferred: Int
        get() = prefs.getInt("total_transferred", 0)
        set(value) = prefs.edit().putInt("total_transferred", value).apply()

    fun resetHistory() {
        prefs.edit()
            .putLong("last_transfer_timestamp", 0L)
            .putInt("total_transferred", 0)
            .apply()
    }
}
