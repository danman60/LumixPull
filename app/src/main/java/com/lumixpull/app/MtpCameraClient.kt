package com.lumixpull.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import android.mtp.MtpConstants
import android.mtp.MtpObjectInfo
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val ACTION_USB_PERMISSION = "com.lumixpull.app.USB_PERMISSION"
private const val PANASONIC_VENDOR_ID = 0x04DA

data class MtpPhoto(
    val objectHandle: Int,
    val name: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val parentHandle: Int
)

class MtpCameraClient(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var mtpDevice: MtpDevice? = null
    private var usbConnection: UsbDeviceConnection? = null

    fun findCamera(): UsbDevice? {
        return usbManager.deviceList.values.firstOrNull { it.vendorId == PANASONIC_VENDOR_ID }
    }

    fun hasPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    suspend fun requestPermission(device: UsbDevice): Boolean = suspendCancellableCoroutine { cont ->
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                context.unregisterReceiver(this)
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (cont.isActive) cont.resume(granted)
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), flags)
        usbManager.requestPermission(device, permissionIntent)

        cont.invokeOnCancellation {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    fun getDeviceDebugInfo(device: UsbDevice): String {
        val debug = StringBuilder()
        debug.appendLine("USB Device: ${device.productName}")
        debug.appendLine("VID=0x${"%04X".format(device.vendorId)} PID=0x${"%04X".format(device.productId)}")
        debug.appendLine("Device class: ${device.deviceClass} subclass: ${device.deviceSubclass} protocol: ${device.deviceProtocol}")
        debug.appendLine("Interface count: ${device.interfaceCount}")
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            debug.appendLine("  Interface $i: class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} protocol=${iface.interfaceProtocol} name=${iface.name}")
            val className = when (iface.interfaceClass) {
                6 -> "Still Image (PTP/MTP)"
                8 -> "Mass Storage"
                255 -> "Vendor Specific"
                else -> "Unknown"
            }
            debug.appendLine("    -> $className")
            debug.appendLine("    Endpoints: ${iface.endpointCount}")
        }
        return debug.toString()
    }

    suspend fun connect(device: UsbDevice): String {
        val debug = StringBuilder()
        debug.appendLine(getDeviceDebugInfo(device))

        val connection = usbManager.openDevice(device)
            ?: throw Exception("Failed to open USB device")
        usbConnection = connection

        val mtp = MtpDevice(device)
        if (!mtp.open(connection)) {
            connection.close()
            throw Exception("MTP open failed. USB interfaces:\n${getDeviceDebugInfo(device)}\nTry switching camera to Tether mode.")
        }
        mtpDevice = mtp

        val deviceInfo = mtp.deviceInfo
        debug.appendLine("MTP connected: ${deviceInfo?.manufacturer} ${deviceInfo?.model}")
        debug.appendLine("Serial: ${deviceInfo?.serialNumber}")

        // Log supported operations
        val ops = deviceInfo?.operationsSupported
        debug.appendLine("Supported operations: ${ops?.size ?: 0}")
        if (ops != null) {
            val opNames = ops.take(20).joinToString(", ") { "0x${"%04X".format(it)}" }
            debug.appendLine("  $opNames${if (ops.size > 20) "..." else ""}")
        }

        // Wait for camera to initialize storage after MTP session opens
        debug.appendLine("Waiting for storage initialization...")
        delay(2000)

        val storageIds = mtp.storageIds
        debug.appendLine("Storage units: ${storageIds?.size ?: 0}")
        storageIds?.forEach { id ->
            val info = mtp.getStorageInfo(id)
            debug.appendLine("  Storage $id (0x${"%08X".format(id)}): desc=${info?.description} volId=${info?.volumeIdentifier}")
            debug.appendLine("    capacity=${formatSize(info?.maxCapacity ?: 0)} free=${formatSize(info?.freeSpace ?: 0)}")
        }

        // If storage shows 0 capacity, try waiting longer and re-checking
        val needsRetry = storageIds?.all { id ->
            val info = mtp.getStorageInfo(id)
            (info?.maxCapacity ?: 0L) == 0L
        } ?: true

        if (needsRetry) {
            debug.appendLine("Storage shows 0 capacity, waiting 3s and retrying...")
            delay(3000)
            storageIds?.forEach { id ->
                val info = mtp.getStorageInfo(id)
                debug.appendLine("  Retry Storage $id: capacity=${formatSize(info?.maxCapacity ?: 0)} free=${formatSize(info?.freeSpace ?: 0)}")
            }
        }

        return debug.toString()
    }

    /**
     * Fast scan: just get object handles without querying info on each one.
     * Returns raw handles + count. Skips the slow per-object getObjectInfo() calls.
     */
    fun quickScan(): Pair<IntArray, String> {
        val mtp = mtpDevice ?: throw Exception("Not connected")
        val debug = StringBuilder()
        val allStorage = 0xFFFFFFFF.toInt()

        // Try format-filtered first (instant if camera supports it)
        val jpegHandles = mtp.getObjectHandles(allStorage, MtpConstants.FORMAT_EXIF_JPEG, 0)
        debug.appendLine("EXIF_JPEG filter: ${jpegHandles?.size ?: "null"}")

        if (jpegHandles != null && jpegHandles.isNotEmpty()) {
            debug.appendLine("Fast path: got ${jpegHandles.size} JPEG handles directly")
            return Pair(jpegHandles, debug.toString())
        }

        // Also try JFIF format
        val jfifHandles = mtp.getObjectHandles(allStorage, MtpConstants.FORMAT_JFIF, 0)
        debug.appendLine("JFIF filter: ${jfifHandles?.size ?: "null"}")

        if (jfifHandles != null && jfifHandles.isNotEmpty()) {
            debug.appendLine("Fast path: got ${jfifHandles.size} JFIF handles directly")
            return Pair(jfifHandles, debug.toString())
        }

        // Fallback: get ALL object handles (just the handle list, no per-object info)
        val allHandles = mtp.getObjectHandles(allStorage, 0, 0)
        debug.appendLine("All objects: ${allHandles?.size ?: "null"}")

        if (allHandles == null || allHandles.isEmpty()) {
            throw Exception("No objects found on camera")
        }

        // We have handles but don't know which are JPEGs.
        // Return all handles - we'll filter during transfer by checking info per-file.
        debug.appendLine("No format filter available. Will filter during transfer.")
        return Pair(allHandles, debug.toString())
    }

    /**
     * Get info for a single object. Used during transfer to get name/size.
     */
    fun getInfo(objectHandle: Int): MtpObjectInfo? {
        return mtpDevice?.getObjectInfo(objectHandle)
    }

    /**
     * Full scan with per-object info (slow). Only used if explicitly requested.
     */
    fun scanForJpegs(): Pair<List<MtpPhoto>, String> {
        val mtp = mtpDevice ?: throw Exception("Not connected")
        val debug = StringBuilder()
        val photos = mutableListOf<MtpPhoto>()
        val allStorage = 0xFFFFFFFF.toInt()

        debug.appendLine("Querying all-storages wildcard...")

        val jpegHandles = mtp.getObjectHandles(allStorage, MtpConstants.FORMAT_EXIF_JPEG, 0)
        debug.appendLine("EXIF_JPEG: ${jpegHandles?.size ?: "null"}")

        val jfifHandles = mtp.getObjectHandles(allStorage, MtpConstants.FORMAT_JFIF, 0)
        debug.appendLine("JFIF: ${jfifHandles?.size ?: "null"}")

        val allHandles = mtp.getObjectHandles(allStorage, 0, 0)
        debug.appendLine("All objects: ${allHandles?.size ?: "null"}")

        val handles = mutableSetOf<Int>()
        jpegHandles?.forEach { handles.add(it) }
        jfifHandles?.forEach { handles.add(it) }

        if (handles.isEmpty() && allHandles != null) {
            debug.appendLine("Scanning ${allHandles.size} objects by name...")
            for (handle in allHandles) {
                val info = mtp.getObjectInfo(handle) ?: continue
                if (isJpeg(info.name) || info.format == MtpConstants.FORMAT_EXIF_JPEG || info.format == MtpConstants.FORMAT_JFIF) {
                    handles.add(handle)
                }
            }
        }

        for (handle in handles) {
            val info = mtp.getObjectInfo(handle) ?: continue
            photos.add(
                MtpPhoto(
                    objectHandle = handle,
                    name = info.name,
                    sizeBytes = info.compressedSize.toLong(),
                    dateModified = info.dateModified,
                    parentHandle = info.parent
                )
            )
        }

        debug.appendLine("Total JPEGs found: ${photos.size}")
        return Pair(photos.sortedByDescending { it.dateModified }, debug.toString())
    }

    fun getObject(objectHandle: Int, objectSize: Int = 0): ByteArray? {
        return mtpDevice?.getObject(objectHandle, objectSize)
    }

    fun importFile(objectHandle: Int, destPath: String): Boolean {
        return mtpDevice?.importFile(objectHandle, destPath) ?: false
    }

    /**
     * Try all available methods to download a file from the camera.
     * Returns the temp file path if successful, null if all methods fail.
     */
    fun downloadToFile(objectHandle: Int, destFile: java.io.File, expectedSize: Long): String? {
        val errors = mutableListOf<String>()

        // Method 1: importFile (streaming, fastest)
        try {
            val success = importFile(objectHandle, destFile.absolutePath)
            if (success && destFile.exists() && destFile.length() > 0) {
                return "importFile (${destFile.length()} bytes)"
            }
            errors.add("importFile: success=$success exists=${destFile.exists()} size=${destFile.length()}")
        } catch (e: Exception) {
            errors.add("importFile: ${e.message}")
        }
        destFile.delete()

        // Method 2: getObject with expected size
        try {
            val data = getObject(objectHandle, expectedSize.toInt())
            if (data != null && data.isNotEmpty()) {
                destFile.writeBytes(data)
                return "getObject(size=$expectedSize) (${data.size} bytes)"
            }
            errors.add("getObject(size): data=${data?.size ?: "null"}")
        } catch (e: Exception) {
            errors.add("getObject(size): ${e.message}")
        }
        destFile.delete()

        // Method 3: getObject with size 0
        try {
            val data = getObject(objectHandle, 0)
            if (data != null && data.isNotEmpty()) {
                destFile.writeBytes(data)
                return "getObject(0) (${data.size} bytes)"
            }
            errors.add("getObject(0): data=${data?.size ?: "null"}")
        } catch (e: Exception) {
            errors.add("getObject(0): ${e.message}")
        }

        return null // All methods failed; errors available in the list
    }

    fun getObjectInfo(objectHandle: Int): MtpObjectInfo? {
        return mtpDevice?.getObjectInfo(objectHandle)
    }

    fun disconnect() {
        try { mtpDevice?.close() } catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        mtpDevice = null
        usbConnection = null
    }

    val isConnected: Boolean get() = mtpDevice != null

    private fun isJpeg(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
