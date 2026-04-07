package com.lumixpull.app

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

private const val DJI_VENDOR_ID = 0x2CA3

data class MountedVolume(
    val path: String,
    val name: String,
    val isPrimary: Boolean,
    val fileCount: Int = 0
)

data class MediaFile(
    val file: java.io.File,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    val isVideo: Boolean
)

class VolumeClient(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

    fun findDjiDevice(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { it.vendorId == DJI_VENDOR_ID }
    }

    /**
     * Find all mounted non-primary volumes (USB drives, SD cards exposed by devices).
     * Returns volumes that contain a DCIM folder.
     */
    fun findMountedVolumes(): Pair<List<MountedVolume>, String> {
        val debug = StringBuilder()
        val volumes = mutableListOf<MountedVolume>()

        // Log all USB devices
        val usbDevices = usbManager.deviceList
        debug.appendLine("USB devices: ${usbDevices.size}")
        for ((name, device) in usbDevices) {
            debug.appendLine("  $name: VID=0x${"%04X".format(device.vendorId)} PID=0x${"%04X".format(device.productId)} ${device.productName ?: ""}")
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                val className = when (iface.interfaceClass) {
                    6 -> "PTP/MTP"
                    8 -> "Mass Storage"
                    255 -> "Vendor Specific"
                    else -> "Class ${iface.interfaceClass}"
                }
                debug.appendLine("    Interface $i: $className")
            }
        }

        val storageVolumes = storageManager.storageVolumes
        debug.appendLine("Storage volumes: ${storageVolumes.size}")

        for (volume in storageVolumes) {
            val path = getVolumePath(volume)
            val desc = volume.getDescription(context) ?: "Unknown"
            val state = volume.state
            debug.appendLine("  $desc: primary=${volume.isPrimary} removable=${volume.isRemovable} state=$state path=$path")

            if (volume.isPrimary) continue
            if (state != "mounted") continue
            if (path == null) continue

            val dcim = java.io.File(path, "DCIM")
            val hasDcim = dcim.exists() && dcim.isDirectory
            debug.appendLine("    DCIM: $hasDcim")

            // Count media files if DCIM exists
            val mediaCount = if (hasDcim) countMediaFiles(dcim) else 0

            volumes.add(
                MountedVolume(
                    path = path,
                    name = desc,
                    isPrimary = volume.isPrimary,
                    fileCount = mediaCount
                )
            )
        }

        // Also check common USB mount points
        val extraPaths = listOf("/storage/usb0", "/storage/usb1", "/storage/usbotg")
        for (extraPath in extraPaths) {
            val dcim = java.io.File(extraPath, "DCIM")
            if (dcim.exists() && dcim.isDirectory && volumes.none { it.path == extraPath }) {
                val count = countMediaFiles(dcim)
                debug.appendLine("  Found extra volume: $extraPath ($count media files)")
                volumes.add(MountedVolume(path = extraPath, name = "USB Storage", isPrimary = false, fileCount = count))
            }
        }

        // Check getExternalFilesDirs for additional volumes
        val extDirs = context.getExternalFilesDirs(null)
        for (dir in extDirs) {
            if (dir == null) continue
            val volumeRoot = findVolumeRoot(dir) ?: continue
            if (volumes.any { it.path == volumeRoot.absolutePath }) continue
            val dcim = java.io.File(volumeRoot, "DCIM")
            if (dcim.exists() && dcim.isDirectory) {
                val count = countMediaFiles(dcim)
                debug.appendLine("  Found via extFilesDirs: ${volumeRoot.absolutePath} ($count media files)")
                volumes.add(MountedVolume(path = volumeRoot.absolutePath, name = "External Storage", isPrimary = false, fileCount = count))
            }
        }

        debug.appendLine("Total non-primary volumes with DCIM: ${volumes.size}")
        return Pair(volumes, debug.toString())
    }

    /**
     * Scan a volume's DCIM folder for photos and videos.
     */
    fun scanMedia(volumePath: String): List<MediaFile> {
        val dcim = java.io.File(volumePath, "DCIM")
        if (!dcim.exists()) return emptyList()

        return dcim.walkTopDown()
            .filter { it.isFile && isMedia(it.name) }
            .map { file ->
                MediaFile(
                    file = file,
                    name = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified(),
                    isVideo = isVideo(file.name)
                )
            }
            .sortedByDescending { it.lastModified }
            .toList()
    }

    private fun countMediaFiles(dcimDir: java.io.File): Int {
        return dcimDir.walkTopDown().count { it.isFile && isMedia(it.name) }
    }

    private fun isMedia(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".mp4") || lower.endsWith(".mov")
    }

    private fun isVideo(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".mp4") || lower.endsWith(".mov")
    }

    private fun getVolumePath(volume: StorageVolume): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                volume.directory?.absolutePath
            } else {
                val method = volume.javaClass.getMethod("getPath")
                method.invoke(volume) as? String
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findVolumeRoot(appSpecificDir: java.io.File): java.io.File? {
        var dir = appSpecificDir
        while (dir.parentFile != null) {
            if (dir.name == "Android") {
                return dir.parentFile
            }
            dir = dir.parentFile!!
        }
        return null
    }
}
