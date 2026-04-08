package com.lumixpull.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TransferState {
    IDLE,
    AWAITING_PERMISSION,
    CONNECTING,
    SCANNING,
    PICK_VOLUME,
    READY,
    TRANSFERRING,
    DONE,
    ERROR
}

data class UiState(
    val transferState: TransferState = TransferState.IDLE,
    val deviceProfile: DeviceProfile? = null,
    val cameraDetected: Boolean = false,
    val cameraName: String? = null,
    val hasUsbPermission: Boolean = false,
    val newPhotoCount: Int = 0,
    val totalPhotosOnCard: Int = 0,
    val progress: TransferProgress? = null,
    val result: TransferResult? = null,
    val errorMessage: String? = null,
    val debugLog: String = "",
    val needsStoragePermission: Boolean = false,
    // Volume picker for mounted-volume devices
    val availableVolumes: List<MountedVolume> = emptyList(),
    val selectedVolumePath: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mtpClient = MtpCameraClient(application)
    private val mtpTransferEngine = MtpTransferEngine(application)
    private val volumeClient = VolumeClient(application)
    private val fileTransferEngine = FileTransferEngine(application)
    val prefs = TransferPrefs(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var mtpHandles: IntArray = intArrayOf()
    private var volumeFiles: List<MediaFile> = emptyList()
    private var autoStarted = false

    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                handleDeviceDisconnected()
            }
        }
    }

    init {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(usbDetachReceiver, filter)
        }
    }

    private fun handleDeviceDisconnected() {
        val currentState = _state.value.transferState
        mtpClient.disconnect()
        mtpHandles = intArrayOf()
        volumeFiles = emptyList()

        val msg = when (currentState) {
            TransferState.TRANSFERRING -> "Device disconnected during transfer"
            TransferState.SCANNING, TransferState.CONNECTING -> "Device disconnected"
            else -> "Device unplugged"
        }

        _state.value = _state.value.copy(
            transferState = TransferState.ERROR,
            errorMessage = msg,
            cameraDetected = false,
            debugLog = _state.value.debugLog + "\n$msg"
        )
    }

    fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun setCrashLog(log: String) {
        _state.value = _state.value.copy(
            debugLog = "PREVIOUS CRASH:\n$log",
            errorMessage = "App crashed last run. Debug log has details."
        )
    }

    fun autoStart() {
        if (autoStarted) return
        autoStarted = true
        detectAndConnect()
    }

    // ─── Auto-detect device type via DeviceRegistry ───

    fun detectAndConnect() {
        viewModelScope.launch {
            val debug = StringBuilder()
            _state.value = _state.value.copy(
                transferState = TransferState.CONNECTING,
                errorMessage = null, result = null, progress = null
            )

            val usbManager = getApplication<Application>().getSystemService(Context.USB_SERVICE) as UsbManager
            val usbDevices = usbManager.deviceList.values.toList()
            debug.appendLine("USB devices found: ${usbDevices.size}")

            // Scan all USB devices and match against DeviceRegistry
            for (device in usbDevices) {
                val profile = DeviceRegistry.findByVendorId(device.vendorId)
                debug.appendLine("  ${device.productName ?: "Unknown"} VID=0x${"%04X".format(device.vendorId)} PID=0x${"%04X".format(device.productId)}")

                if (profile != null) {
                    debug.appendLine("  -> Matched: ${profile.brand} (${profile.transferMode})")
                    _state.value = _state.value.copy(
                        deviceProfile = profile,
                        cameraDetected = true,
                        cameraName = device.productName ?: profile.brand,
                        debugLog = debug.toString()
                    )

                    when (profile.transferMode) {
                        TransferMode.MTP -> {
                            startMtp(device, profile, debug)
                            return@launch
                        }
                        TransferMode.VOLUME -> {
                            startVolume(profile, debug)
                            return@launch
                        }
                    }
                }
            }

            // No known device matched — try auto-detection via interface classes
            for (device in usbDevices) {
                val detectedMode = DeviceRegistry.detectTransferMode(device)
                debug.appendLine("  Auto-detect ${device.productName ?: "Unknown"}: $detectedMode")

                val autoProfile = DeviceProfile(
                    vendorId = device.vendorId,
                    brand = device.productName ?: "USB Device",
                    transferMode = detectedMode,
                    subfolder = "USB"
                )

                _state.value = _state.value.copy(
                    deviceProfile = autoProfile,
                    cameraDetected = true,
                    cameraName = device.productName ?: "USB Device",
                    debugLog = debug.toString()
                )

                when (detectedMode) {
                    TransferMode.MTP -> {
                        startMtp(device, autoProfile, debug)
                        return@launch
                    }
                    TransferMode.VOLUME -> {
                        startVolume(autoProfile, debug)
                        return@launch
                    }
                }
            }

            // Check if any volumes with DCIM are mounted (device already connected but no USB match)
            val (volumes, volDebug) = withContext(Dispatchers.IO) { volumeClient.findMountedVolumes() }
            debug.appendLine(volDebug)

            if (volumes.isNotEmpty()) {
                debug.appendLine("Found ${volumes.size} mounted volume(s) with DCIM")
                val fallbackProfile = DeviceProfile(
                    vendorId = 0,
                    brand = "USB Device",
                    transferMode = TransferMode.VOLUME,
                    subfolder = "USB"
                )
                _state.value = _state.value.copy(
                    deviceProfile = fallbackProfile,
                    cameraDetected = true,
                    cameraName = "USB Device",
                    debugLog = debug.toString()
                )
                val accessibleVolumes = volumes.filter { it.accessible }
                if (accessibleVolumes.size == 1 && accessibleVolumes[0].fileCount > 0) {
                    selectVolume(accessibleVolumes[0].path)
                    _state.value = _state.value.copy(
                        availableVolumes = volumes,
                        debugLog = debug.toString()
                    )
                } else {
                    _state.value = _state.value.copy(
                        transferState = TransferState.PICK_VOLUME,
                        availableVolumes = volumes,
                        debugLog = debug.toString()
                    )
                }
                return@launch
            }

            debug.appendLine("No supported device found")
            _state.value = _state.value.copy(
                transferState = TransferState.ERROR,
                errorMessage = "No device detected.\nConnect a camera or drone via USB-C.",
                debugLog = debug.toString()
            )
        }
    }

    // ─── Volume-based transfer (DJI, Blackmagic, Insta360, etc.) ───

    private suspend fun startVolume(profile: DeviceProfile, debug: StringBuilder) {
        // Check if we have file access permission
        if (!hasStoragePermission()) {
            debug.appendLine("MANAGE_EXTERNAL_STORAGE not granted — requesting")
            _state.value = _state.value.copy(
                needsStoragePermission = true,
                transferState = TransferState.PICK_VOLUME,
                availableVolumes = emptyList(),
                debugLog = debug.toString()
            )
            return
        }
        debug.appendLine("Storage permission: granted")

        val (volumes, volDebug) = withContext(Dispatchers.IO) { volumeClient.findMountedVolumes() }
        debug.appendLine(volDebug)

        val accessibleVolumes = volumes.filter { it.accessible }

        if (accessibleVolumes.size == 1 && accessibleVolumes[0].fileCount > 0) {
            // Single accessible volume with files — go straight to scan
            selectVolume(accessibleVolumes[0].path)
            _state.value = _state.value.copy(
                availableVolumes = volumes,
                debugLog = debug.toString()
            )
        } else {
            // Show picker — either multiple volumes, no accessible ones, or need SAF
            _state.value = _state.value.copy(
                transferState = TransferState.PICK_VOLUME,
                availableVolumes = volumes,
                debugLog = debug.toString()
            )
        }
    }

    fun onSafFolderSelected(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(transferState = TransferState.SCANNING)
            try {
                val app = getApplication<Application>()
                val files = withContext(Dispatchers.IO) { volumeClient.scanMediaFromUri(app, uri) }
                volumeFiles = files

                val photoCount = files.count { !it.isVideo }
                val videoCount = files.count { it.isVideo }
                val debug = _state.value.debugLog + "\nSAF scan: $photoCount photos, $videoCount videos from ${uri.lastPathSegment}"

                _state.value = _state.value.copy(
                    transferState = if (files.isNotEmpty()) TransferState.READY else TransferState.DONE,
                    newPhotoCount = files.size,
                    totalPhotosOnCard = files.size,
                    result = if (files.isEmpty()) TransferResult(0, 0, emptyList()) else null,
                    debugLog = debug
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    transferState = TransferState.ERROR,
                    errorMessage = "SAF scan failed: ${e.message}\n${e.stackTraceToString().take(300)}"
                )
            }
        }
    }

    fun selectVolume(volumePath: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                transferState = TransferState.SCANNING,
                selectedVolumePath = volumePath
            )

            try {
                val files = withContext(Dispatchers.IO) { volumeClient.scanMedia(volumePath) }
                volumeFiles = files

                val photoCount = files.count { !it.isVideo }
                val videoCount = files.count { it.isVideo }
                val debug = _state.value.debugLog + "\nScanned: $photoCount photos, $videoCount videos"

                _state.value = _state.value.copy(
                    transferState = if (files.isNotEmpty()) TransferState.READY else TransferState.DONE,
                    newPhotoCount = files.size,
                    totalPhotosOnCard = files.size,
                    result = if (files.isEmpty()) TransferResult(0, 0, emptyList()) else null,
                    debugLog = debug
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    transferState = TransferState.ERROR,
                    errorMessage = "Scan failed: ${e.message}"
                )
            }
        }
    }

    fun transferVolume() {
        if (volumeFiles.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(transferState = TransferState.TRANSFERRING)
            try {
                val subfolder = _state.value.deviceProfile?.subfolder ?: "USB"
                val result = fileTransferEngine.transferFiles(volumeFiles, subfolder, prefs) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
                if (result.transferred > 0) {
                    prefs.totalTransferred = prefs.totalTransferred + result.transferred
                }
                _state.value = _state.value.copy(transferState = TransferState.DONE, result = result)
                volumeFiles = emptyList()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    transferState = TransferState.ERROR,
                    errorMessage = "Transfer failed: ${e.message}"
                )
            }
        }
    }

    fun testTransferVolume() {
        if (volumeFiles.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(transferState = TransferState.TRANSFERRING)
            try {
                val subfolder = _state.value.deviceProfile?.subfolder ?: "USB"
                val result = fileTransferEngine.transferFiles(volumeFiles.take(3), subfolder, prefs) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
                _state.value = _state.value.copy(transferState = TransferState.DONE, result = result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "Test failed: ${e.message}")
            }
        }
    }

    // ─── MTP transfer (Lumix, Canon, Nikon, Sony, etc.) ───

    private suspend fun startMtp(device: android.hardware.usb.UsbDevice, profile: DeviceProfile, debug: StringBuilder) {
        if (!mtpClient.hasPermission(device)) {
            _state.value = _state.value.copy(transferState = TransferState.AWAITING_PERMISSION, debugLog = debug.toString())
            val granted = mtpClient.requestPermission(device)
            if (!granted) {
                _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "USB permission denied.", debugLog = debug.toString())
                return
            }
        }

        debug.appendLine("USB permission granted")
        _state.value = _state.value.copy(hasUsbPermission = true, transferState = TransferState.CONNECTING, debugLog = debug.toString())

        try {
            val connectDebug = withContext(Dispatchers.IO) { mtpClient.connect(device) }
            debug.appendLine(connectDebug)

            if (!mtpClient.isConnected) throw Exception("Connection lost after connect")

            _state.value = _state.value.copy(transferState = TransferState.SCANNING, debugLog = debug.toString())

            val (handles, scanDebug) = withContext(Dispatchers.IO) { mtpClient.quickScan(profile.mtpQuirks) }
            debug.appendLine(scanDebug)
            mtpHandles = handles

            _state.value = _state.value.copy(
                transferState = if (handles.isNotEmpty()) TransferState.READY else TransferState.DONE,
                newPhotoCount = handles.size,
                totalPhotosOnCard = handles.size,
                result = if (handles.isEmpty()) TransferResult(0, 0, emptyList()) else null,
                debugLog = debug.toString()
            )
        } catch (e: Exception) {
            debug.appendLine("Error: ${e.message}")
            _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "${e.message}", debugLog = debug.toString())
        }
    }

    fun transferMtp() {
        if (mtpHandles.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(transferState = TransferState.TRANSFERRING)
            try {
                if (!mtpClient.isConnected) throw Exception("Camera disconnected")
                val subfolder = _state.value.deviceProfile?.subfolder ?: "Lumix"
                val result = mtpTransferEngine.transferFromHandles(mtpClient, mtpHandles, subfolder, prefs) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
                if (result.transferred > 0) prefs.totalTransferred = prefs.totalTransferred + result.transferred
                _state.value = _state.value.copy(transferState = TransferState.DONE, result = result)
                mtpHandles = intArrayOf()
            } catch (e: Exception) {
                _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "Transfer failed: ${e.message}")
            }
        }
    }

    fun testTransferMtp() {
        if (mtpHandles.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(transferState = TransferState.TRANSFERRING)
            try {
                if (!mtpClient.isConnected) throw Exception("Camera disconnected")
                val subfolder = _state.value.deviceProfile?.subfolder ?: "Lumix"
                val result = mtpTransferEngine.transferFromHandles(mtpClient, mtpHandles.take(3).toIntArray(), subfolder, prefs) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
                _state.value = _state.value.copy(transferState = TransferState.DONE, result = result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "Test failed: ${e.message}")
            }
        }
    }

    // ─── Dispatchers (route to correct transfer mode) ───

    fun transfer() {
        when (_state.value.deviceProfile?.transferMode) {
            TransferMode.MTP -> transferMtp()
            TransferMode.VOLUME -> transferVolume()
            null -> {}
        }
    }

    fun testTransfer() {
        when (_state.value.deviceProfile?.transferMode) {
            TransferMode.MTP -> testTransferMtp()
            TransferMode.VOLUME -> testTransferVolume()
            null -> {}
        }
    }

    // ─── Common ───

    fun resetHistory() {
        prefs.resetHistory()
        _state.value = _state.value.copy(transferState = TransferState.IDLE)
    }

    override fun onCleared() {
        super.onCleared()
        try { getApplication<Application>().unregisterReceiver(usbDetachReceiver) } catch (_: Exception) {}
        mtpClient.disconnect()
    }
}
