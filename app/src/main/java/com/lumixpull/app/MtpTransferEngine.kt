package com.lumixpull.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.mtp.MtpConstants
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

data class TransferProgress(
    val totalFiles: Int,
    val completedFiles: Int,
    val currentFileName: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val failed: List<String> = emptyList()
)

data class TransferResult(
    val transferred: Int,
    val failed: Int,
    val errors: List<String>
)

class MtpTransferEngine(private val context: Context) {

    /**
     * Transfer directly from raw MTP handles.
     * Queries object info per-file during transfer (no slow pre-scan).
     * Skips non-JPEG files and duplicates already in MediaStore.
     */
    suspend fun transferFromHandles(
        client: MtpCameraClient,
        handles: IntArray,
        appContext: Context,
        onProgress: (TransferProgress) -> Unit
    ): TransferResult = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        var transferred = 0
        var skipped = 0
        var bytesTransferred = 0L

        // Build set of already-transferred filenames to skip duplicates
        val existingNames = getExistingLumixPhotos()

        val tempDir = File(context.cacheDir, "lumix_transfer")
        tempDir.mkdirs()

        for ((index, handle) in handles.withIndex()) {
            // Check camera connection before each file
            if (!client.isConnected) {
                failed.add("Camera disconnected — stopping transfer")
                break
            }

            // Get object info for this handle
            val info = try {
                client.getInfo(handle)
            } catch (e: Exception) {
                // If getInfo throws, camera likely disconnected
                failed.add("Camera disconnected: ${e.message}")
                break
            }

            if (info == null) {
                skipped++
                continue
            }

            // Skip non-JPEG files
            val name = info.name ?: "unknown"
            val isJpeg = name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") } ||
                info.format == MtpConstants.FORMAT_EXIF_JPEG || info.format == MtpConstants.FORMAT_JFIF
            if (!isJpeg) {
                skipped++
                continue
            }

            // Skip already transferred
            if (existingNames.contains(name)) {
                skipped++
                continue
            }

            onProgress(
                TransferProgress(
                    totalFiles = handles.size - skipped,
                    completedFiles = transferred,
                    currentFileName = "$name (${formatSize(info.compressedSize.toLong())})",
                    bytesTransferred = bytesTransferred,
                    totalBytes = 0 // Unknown total since we're streaming
                )
            )

            val tempFile = File(tempDir, name)
            try {
                val method = client.downloadToFile(handle, tempFile, info.compressedSize.toLong())

                if (method == null || !tempFile.exists() || tempFile.length() == 0L) {
                    throw Exception("Download failed (all methods returned empty)")
                }

                saveToMediaStore(name, tempFile, info.dateModified.toLong())
                existingNames.add(name) // Track for duplicate prevention
                bytesTransferred += tempFile.length()
                transferred++
            } catch (e: Exception) {
                val msg = e.message ?: ""
                // Detect disconnect-like errors and stop entirely
                if (msg.contains("disconnect", ignoreCase = true) ||
                    msg.contains("device not found", ignoreCase = true) ||
                    msg.contains("USB", ignoreCase = true) ||
                    !client.isConnected) {
                    failed.add("Camera disconnected during $name: $msg")
                    tempFile.delete()
                    break
                }
                failed.add("$name: $msg")
                if (failed.size >= 5 && transferred == 0) {
                    failed.add("STOPPED: 5 failures with 0 successes")
                    tempFile.delete()
                    break
                }
            } finally {
                tempFile.delete()
            }
        }

        tempDir.delete()

        onProgress(
            TransferProgress(
                totalFiles = transferred + failed.size,
                completedFiles = transferred,
                currentFileName = "",
                bytesTransferred = bytesTransferred,
                totalBytes = bytesTransferred,
                failed = failed
            )
        )

        TransferResult(
            transferred = transferred,
            failed = failed.size,
            errors = listOf("Skipped: $skipped (non-JPEG or already transferred)") + failed
        )
    }

    /**
     * Query MediaStore for existing photos in Pictures/Lumix to avoid duplicates.
     */
    private fun getExistingLumixPhotos(): MutableSet<String> {
        val names = mutableSetOf<String>()
        try {
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("%Lumix%"),
                null
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0)
                    if (name != null) names.add(name)
                }
            }
        } catch (_: Exception) {}
        return names
    }

    // Keep old method for compatibility with MtpPhoto list
    suspend fun transferPhotos(
        client: MtpCameraClient,
        photos: List<MtpPhoto>,
        onProgress: (TransferProgress) -> Unit
    ): TransferResult {
        val handles = photos.map { it.objectHandle }.toIntArray()
        return transferFromHandles(client, handles, context, onProgress)
    }

    private fun saveToMediaStore(name: String, sourceFile: File, dateTaken: Long) {
        val resolver = context.contentResolver

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, dateTaken * 1000)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.SIZE, sourceFile.length())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Lumix")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw Exception("MediaStore insert returned null")

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                FileInputStream(sourceFile).use { inputStream ->
                    val copied = inputStream.copyTo(outputStream, bufferSize = 65536)
                    if (copied == 0L) {
                        throw Exception("copyTo returned 0 bytes (source: ${sourceFile.length()})")
                    }
                }
            } ?: throw Exception("openOutputStream returned null")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val update = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                resolver.update(uri, update, null, null)
            }

            notifyMediaScanner(uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun notifyMediaScanner(uri: Uri) {
        try {
            val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.DATA), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val path = it.getString(0)
                    if (path != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("image/jpeg"), null)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
