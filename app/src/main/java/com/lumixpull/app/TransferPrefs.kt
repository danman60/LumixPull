package com.lumixpull.app

import android.content.Context

class TransferPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("lumixpull", Context.MODE_PRIVATE)
    private val transferLog = context.getSharedPreferences("transfer_log", Context.MODE_PRIVATE)

    var lastTransferTimestamp: Long
        get() = prefs.getLong("last_transfer_timestamp", 0L)
        set(value) = prefs.edit().putLong("last_transfer_timestamp", value).apply()

    var totalTransferred: Int
        get() = prefs.getInt("total_transferred", 0)
        set(value) = prefs.edit().putInt("total_transferred", value).apply()

    /**
     * Check if a file has already been transferred.
     */
    fun isTransferred(filename: String): Boolean {
        return transferLog.getBoolean(filename, false)
    }

    /**
     * Mark a file as successfully transferred.
     */
    fun markTransferred(filename: String) {
        transferLog.edit().putBoolean(filename, true).apply()
    }

    /**
     * Mark multiple files as transferred in a batch.
     */
    fun markTransferred(filenames: Collection<String>) {
        val editor = transferLog.edit()
        for (name in filenames) {
            editor.putBoolean(name, true)
        }
        editor.apply()
    }

    /**
     * Get count of all transferred files.
     */
    fun transferredCount(): Int {
        return transferLog.all.size
    }

    /**
     * Get all transferred filenames (for debug).
     */
    fun allTransferred(): Set<String> {
        return transferLog.all.keys
    }

    fun resetHistory() {
        prefs.edit()
            .putLong("last_transfer_timestamp", 0L)
            .putInt("total_transferred", 0)
            .apply()
        transferLog.edit().clear().apply()
    }
}
