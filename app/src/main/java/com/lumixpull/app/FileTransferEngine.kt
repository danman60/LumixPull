package com.lumixpull.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream

/**
 * Transfers files from a mounted volume (DJI, SD card, etc.) to MediaStore.
 * Handles both photos (Pictures/DJI) and videos (Movies/DJI).
 */
class FileTransferEngine(private val context: Context) {

    suspend fun transferFiles(
        files: List<MediaFile>,
        subfolder: String = "DJI",
        onProgress: (TransferProgress) -> Unit
    ): TransferResult = withContext(Dispatchers.IO) {
        val totalBytes = files.sumOf { it.sizeBytes }
        var bytesTransferred = 0L
        val failed = mutableListOf<String>()
        var transferred = 0
        var skipped = 0

        val existingPhotos = getExistingFiles("${Environment.DIRECTORY_PICTURES}/$subfolder")
        val existingVideos = getExistingFiles("${Environment.DIRECTORY_MOVIES}/$subfolder")
        val allExisting = existingPhotos + existingVideos

        for ((index, file) in files.withIndex()) {
            // Skip duplicates
            if (allExisting.contains(file.name)) {
                skipped++
                continue
            }

            onProgress(
                TransferProgress(
                    totalFiles = files.size - skipped,
                    completedFiles = transferred,
                    currentFileName = "${file.name} (${formatSize(file.sizeBytes)})",
                    bytesTransferred = bytesTransferred,
                    totalBytes = totalBytes
                )
            )

            try {
                if (file.isVideo) {
                    saveVideoToMediaStore(file, subfolder)
                } else {
                    savePhotoToMediaStore(file, subfolder)
                }
                bytesTransferred += file.sizeBytes
                transferred++
            } catch (e: Exception) {
                failed.add("${file.name}: ${e.message}")
                if (failed.size >= 5 && transferred == 0) {
                    failed.add("STOPPED: 5 failures with 0 successes")
                    break
                }
            }
        }

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
            errors = listOf("Skipped: $skipped (already transferred)") + failed
        )
    }

    private fun savePhotoToMediaStore(media: MediaFile, subfolder: String) {
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, media.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, media.lastModified)
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.SIZE, media.sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$subfolder")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore insert failed")

        try {
            copyFileToUri(media.file, uri)
            finalizePending(uri, MediaStore.Images.Media.IS_PENDING)
            notifyMediaScanner(uri, "image/jpeg")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveVideoToMediaStore(media: MediaFile, subfolder: String) {
        val resolver = context.contentResolver
        val mimeType = if (media.name.lowercase().endsWith(".mov")) "video/quicktime" else "video/mp4"

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, media.name)
            put(MediaStore.Video.Media.MIME_TYPE, mimeType)
            put(MediaStore.Video.Media.DATE_TAKEN, media.lastModified)
            put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Video.Media.SIZE, media.sizeBytes)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$subfolder")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("MediaStore insert failed")

        try {
            copyFileToUri(media.file, uri)
            finalizePending(uri, MediaStore.Video.Media.IS_PENDING)
            notifyMediaScanner(uri, mimeType)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun copyFileToUri(source: java.io.File, destUri: Uri) {
        val resolver = context.contentResolver
        resolver.openOutputStream(destUri)?.use { out ->
            FileInputStream(source).use { input ->
                val copied = input.copyTo(out, bufferSize = 131072)
                if (copied == 0L) throw Exception("Copied 0 bytes (source: ${source.length()})")
            }
        } ?: throw Exception("openOutputStream returned null")
    }

    private fun finalizePending(uri: Uri, pendingColumn: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val update = ContentValues().apply { put(pendingColumn, 0) }
            context.contentResolver.update(uri, update, null, null)
        }
    }

    private fun notifyMediaScanner(uri: Uri, mimeType: String) {
        try {
            val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val path = it.getString(0)
                    if (path != null) {
                        MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mimeType), null)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun getExistingFiles(relativePath: String): Set<String> {
        val names = mutableSetOf<String>()
        try {
            // Check both images and videos
            for (contentUri in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
                val cursor = context.contentResolver.query(
                    contentUri,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf("$relativePath%"),
                    null
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        it.getString(0)?.let { name -> names.add(name) }
                    }
                }
            }
        } catch (_: Exception) {}
        return names
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
