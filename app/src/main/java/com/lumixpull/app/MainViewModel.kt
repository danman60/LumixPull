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

enum class TransferState {
    IDLE,
    AWAITING_PERMISSION,
    CONNECTING,
    SCANNING,
    READY,
    TRANSFERRING,
    DONE,
    ERROR
}

data class UiState(
    val transferState: TransferState = TransferState.IDLE,
    val cameraDetected: Boolean = false,
    val cameraName: String? = null,
    val hasUsbPermission: Boolean = false,
    val newPhotoCount: Int = 0,
    val totalPhotosOnCard: Int = 0,
    val progress: TransferProgress? = null,
    val result: TransferResult? = null,
    val errorMessage: String? = null,
    val debugLog: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val mtpClient = MtpCameraClient(application)
    private val mtpTransferEngine = MtpTransferEngine(application)
    val prefs = TransferPrefs(application)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var mtpHandles: IntArray = intArrayOf()
    private var autoStarted = false

    // Listen for USB detach to handle camera disconnect gracefully
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                handleCameraDisconnected()
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

    private fun handleCameraDisconnected() {
        val currentState = _state.value.transferState
        mtpClient.disconnect()
        mtpHandles = intArrayOf()

        val msg = when (currentState) {
            TransferState.TRANSFERRING -> "Camera disconnected during transfer"
            TransferState.SCANNING, TransferState.CONNECTING -> "Camera disconnected"
            else -> "Camera unplugged"
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

    /** Auto-start MTP connection on app open */
    fun autoStart() {
        if (autoStarted) return
        autoStarted = true
        startMtp()
    }

    fun startMtp() {
        viewModelScope.launch {
            val debug = StringBuilder()

            // Reset state for fresh connection
            _state.value = _state.value.copy(
                transferState = TransferState.CONNECTING,
                errorMessage = null,
                result = null,
                progress = null
            )

            val device = mtpClient.findCamera()
            if (device == null) {
                debug.appendLine("No Panasonic camera found on USB")
                _state.value = _state.value.copy(
                    transferState = TransferState.ERROR,
                    errorMessage = "No camera detected on USB.\nConnect camera in Tether mode.",
                    debugLog = debug.toString()
                )
                return@launch
            }

            debug.appendLine("Found: ${device.productName} (VID=0x${"%04X".format(device.vendorId)} PID=0x${"%04X".format(device.productId)})")
            _state.value = _state.value.copy(cameraDetected = true, cameraName = device.productName, debugLog = debug.toString())

            if (!mtpClient.hasPermission(device)) {
                _state.value = _state.value.copy(
                    transferState = TransferState.AWAITING_PERMISSION,
                    debugLog = debug.toString()
                )
                val granted = mtpClient.requestPermission(device)
                if (!granted) {
                    _state.value = _state.value.copy(
                        transferState = TransferState.ERROR,
                        errorMessage = "USB permission denied.",
                        debugLog = debug.toString()
                    )
                    return@launch
                }
            }

            debug.appendLine("USB permission granted")
            _state.value = _state.value.copy(hasUsbPermission = true, transferState = TransferState.CONNECTING, debugLog = debug.toString())

            try {
                val connectDebug = withContext(Dispatchers.IO) { mtpClient.connect(device) }
                debug.appendLine(connectDebug)

                if (!mtpClient.isConnected) {
                    throw Exception("Camera connection lost after connect")
                }

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
                _state.value = _state.value.copy(
                    transferState = TransferState.ERROR,
                    errorMessage = "${e.message}",
                    debugLog = debug.toString()
                )
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

    fun transferMtp() {
        if (mtpHandles.isEmpty()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(transferState = TransferState.TRANSFERRING)
            try {
                if (!mtpClient.isConnected) throw Exception("Camera disconnected")
                val result = mtpTransferEngine.transferFromHandles(mtpClient, mtpHandles, getApplication()) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
                if (result.transferred > 0) {
                    prefs.totalTransferred = prefs.totalTransferred + result.transferred
                }
                _state.value = _state.value.copy(transferState = TransferState.DONE, result = result)
                mtpHandles = intArrayOf()
            } catch (e: Exception) {
                _state.value = _state.value.copy(transferState = TransferState.ERROR, errorMessage = "Transfer failed: ${e.message}")
            }
        }
    }

    fun transferAllMtp() {
        transferMtp()
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
