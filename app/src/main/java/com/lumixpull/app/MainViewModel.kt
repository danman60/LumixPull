package com.lumixpull.app

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DeviceType {
    LUMIX,  // Panasonic camera — MTP transfer
    DJI,    // DJI drone — mounted volume transfer
    UNKNOWN
}

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
    val deviceType: DeviceType = DeviceType.UNKNOWN,
    val cameraDetected: Boolean = false,
    val cameraName: String? = null,
    val hasUsbPermission: Boolean = false,
    val newPhotoCount: Int = 0,
    val totalPhotosOnCard: Int = 0,
    val progress: TransferProgress? = null,
    val result: TransferResult? = null,
    val errorMessage: String? = null,
    val debugLog: String = "",
    // Volume picker for DJI
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

    // ─── Auto-detect device type ───

    fun detectAndConnect() {
        viewModelScope.launch {
            val debug = StringBuilder()
            _state.value = _state.value.copy(
                transferState = TransferState.CONNECTING,
                errorMessage = null, result = null, progress = null
            )

            // Check for DJI first (mounts as volume)
            val djiDevice = volumeClient.findDjiDevice()
            if (djiDevice != null) {
                debug.appendLine("DJI device found: ${djiDevice.productName} (VID=0x${"%04X".format(djiDevice.vendorId)} PID=0x${"%04X".format(djiDevice.productId)})")
                _state.value = _state.value.copy(
                    deviceType = DeviceType.DJI,
                    cameraDetected = true,
                    cameraName = djiDevice.productName ?: "DJI Drone",
                    debugLog = debug.toString()
                )
                startDji(debug)
                return@launch
            }

            // Check for Lumix camera (MTP)
            val lumixDevice = mtpClient.findCamera()
            if (lumixDevice != null) {
                debug.appendLine("Lumix found: ${lumixDevice.productName} (VID=0x${"%04X".format(lumixDevice.vendorId)} PID=0x${"%04X".format(lumixDevice.productId)})")
                _state.value = _state.value.copy(
                    deviceType = DeviceType.LUMIX,
                    cameraDetected = true,
                    cameraName = lumixDevice.productName,
                    debugLog = debug.toString()
                )
                startLumix(lumixDevice, debug)
                return@launch
            }

            // Check if any volumes with DCIM are mounted (generic device)
            val (volumes, volDebug) = withContext(Dispatchers.IO) { volumeClient.findMountedVolumes() }
            debug.appendLine(volDebug)

            if (volumes.isNotEmpty()) {
                debug.appendLine("Found ${volumes.size} mounted volume(s) with DCIM")
                _state.value = _state.value.copy(
                    deviceType = DeviceType.DJI, // Treat any mounted volume as DJI-style
                    cameraDetected = true,
                    cameraName = "USB Device",
                    debugLog = debug.toString()
                )
                handleVolumes(volumes, debug)
                return@launch
            }

            debug.appendLine("No supported device found")
            _state.value = _state.value.copy(
                transferState = TransferState.ERROR,
                errorMessage = "No device detected.\nConnect Lumix (Tether mode) or DJI drone via USB-C.",
                debugLog = debug.toString()
            )
        }
    }

    // ─── DJI / Volume-based transfer ───

    private suspend fun startDji(debug: StringBuilder) {
        // Find mounted volumes
        val (volumes, volDebug) = withContext(Dispatchers.IO) { volumeClient.findMountedVolumes() }
        debug.appendLine(volDebug)

        if (volumes.isEmpty()) {
            debug.appendLine("DJI detected but no mounted volumes found")
            _state.value = _state.value.copy(
                transferState = TransferState.ERROR,
                errorMessage = "DJI detected but storage not mounted.\nTry unplugging and reconnecting.",
                debugLog = debug.toString()
            )
            return
        }

        handleVolumes(volumes, debug)
    }

    private fun handleVolumes(volumes: List<MountedVolume>, debug: StringBuilder) {
        if (volumes.size == 1) {
            // Single volume — go straight to scan
            selectVolume(volumes[0].path)
            _state.value = _state.value.copy(
                availableVolumes = volumes,
                debugLog = debug.toString()
            )
        } else {
            // Multiple volumes — let user pick
            _state.value = _state.value.copy(
                transferState = TransferState.PICK_VOLUME,
                availableVolumes = volumes,
                debugLog = debug.toString()
            )
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
                val subfolder = if (_state.value.deviceType == DeviceType.DJI) "DJI" else "USB"
                val result = fileTransferEngine.transferFiles(volumeFiles, subfolder) { progress ->
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
                val subfolder = if (_state.value.deviceType == DeviceType.DJI) "DJI" else "USB"
                val result = fileTransferEngine.transferFiles(volumeFiles.take(3), subfolder) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
                _state.value = _state.value.copy(transferState = TransferState.DONE, result = result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "Test failed: ${e.message}")
            }
        }
    }

    // ─── Lumix / MTP transfer ───

    private suspend fun startLumix(device: android.hardware.usb.UsbDevice, debug: StringBuilder) {
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

            val (handles, scanDebug) = withContext(Dispatchers.IO) { mtpClient.quickScan() }
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
                val result = mtpTransferEngine.transferFromHandles(mtpClient, mtpHandles, getApplication()) { progress ->
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
                val result = mtpTransferEngine.transferFromHandles(mtpClient, mtpHandles.take(3).toIntArray(), getApplication()) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
                _state.value = _state.value.copy(transferState = TransferState.DONE, result = result)
            } catch (e: Exception) {
                _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "Test failed: ${e.message}")
            }
        }
    }

    // ─── Dispatchers (route to correct device) ───

    fun transfer() {
        when (_state.value.deviceType) {
            DeviceType.LUMIX -> transferMtp()
            DeviceType.DJI -> transferVolume()
            DeviceType.UNKNOWN -> {}
        }
    }

    fun testTransfer() {
        when (_state.value.deviceType) {
            DeviceType.LUMIX -> testTransferMtp()
            DeviceType.DJI -> testTransferVolume()
            DeviceType.UNKNOWN -> {}
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
