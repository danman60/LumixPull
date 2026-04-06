package com.lumixpull.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check for crash from previous run
        val crashLog = LumixPullApp.getLastCrash(this)
        if (crashLog != null) {
            viewModel.setCrashLog(crashLog)
            LumixPullApp.clearCrash(this)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.state.collectAsState()

                    // Auto-start MTP connection when app opens
                    LaunchedEffect(Unit) {
                        viewModel.autoStart()
                    }

                    LumixPullScreen(
                        state = state,
                        onRetry = { viewModel.startMtp() },
                        onTransfer = { viewModel.transferMtp() },
                        onTransferAll = { viewModel.transferAllMtp() },
                        onTestTransfer = { viewModel.testTransferMtp() },
                        onResetHistory = viewModel::resetHistory
                    )
                }
            }
        }
    }
}

@Composable
fun LumixPullScreen(
    state: UiState,
    onRetry: () -> Unit,
    onTransfer: () -> Unit,
    onTransferAll: () -> Unit,
    onResetHistory: () -> Unit,
    onTestTransfer: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("LumixPull", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Lumix S5II Photo Transfer", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.height(24.dp))

        // State-specific content
        when (state.transferState) {
            TransferState.IDLE -> IdleView(onRetry)
            TransferState.AWAITING_PERMISSION -> PermissionView()
            TransferState.CONNECTING -> ConnectingView()
            TransferState.SCANNING -> ScanningView()
            TransferState.READY -> ReadyView(state, onTransfer, onTransferAll, onTestTransfer)
            TransferState.TRANSFERRING -> TransferringView(state.progress)
            TransferState.DONE -> DoneView(state, onRetry, onResetHistory)
            TransferState.ERROR -> ErrorView(state.errorMessage, onRetry)
        }

        // Debug panel
        if (state.debugLog.isNotBlank()) {
            Spacer(modifier = Modifier.height(32.dp))
            DebugPanel(state.debugLog)
        }
    }
}

@Composable
fun IdleView(onConnect: () -> Unit) {
    SetupInstructions()
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Connect", fontSize = 16.sp)
    }
}

@Composable
fun SetupInstructions() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Setup", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(16.dp))

            InstructionStep(1, "Camera: USB Mode", "Menu > Setup (wrench) > USB > set to PC(Tether)")
            Spacer(modifier = Modifier.height(12.dp))
            InstructionStep(2, "Connect", "Plug camera into phone with USB-C cable")
            Spacer(modifier = Modifier.height(12.dp))
            InstructionStep(3, "Allow Access", "Tap Allow on the USB permission popup")
            Spacer(modifier = Modifier.height(12.dp))
            InstructionStep(4, "Transfer", "Tap Transfer Photos (or Test with 3 first)")
            Spacer(modifier = Modifier.height(12.dp))
            InstructionStep(5, "Google Photos", "Library > Photos on device > enable Lumix folder")
        }
    }
}

@Composable
fun InstructionStep(number: Int, title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "$number",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(24.dp)
        )
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun PermissionView() {
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(16.dp))
    Text("Grant USB permission on the popup...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ConnectingView() {
    CircularProgressIndicator(modifier = Modifier.size(48.dp))
    Spacer(modifier = Modifier.height(16.dp))
    Text("Connecting to camera...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun ScanningView() {
    CircularProgressIndicator(modifier = Modifier.size(64.dp))
    Spacer(modifier = Modifier.height(24.dp))
    Text("Scanning for photos...", fontSize = 18.sp, fontWeight = FontWeight.Medium)
}

@Composable
fun ReadyView(state: UiState, onTransfer: () -> Unit, onTransferAll: () -> Unit, onTestTransfer: () -> Unit = {}) {
    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(24.dp))
    Text("${state.newPhotoCount} photos found", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
    Text("${state.totalPhotosOnCard} total on card", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(32.dp))
    Button(
        onClick = onTransfer,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text("Transfer Photos", fontSize = 16.sp) }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onTransferAll,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text("Transfer All", fontSize = 14.sp) }
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedButton(
        onClick = onTestTransfer,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.tertiary)
    ) { Text("Test (3 photos)", fontSize = 14.sp) }
}

@Composable
fun TransferringView(progress: TransferProgress?) {
    if (progress == null) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        return
    }
    val fraction = if (progress.totalBytes > 0) progress.bytesTransferred.toFloat() / progress.totalBytes.toFloat() else 0f
    CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(80.dp), strokeWidth = 6.dp)
    Spacer(modifier = Modifier.height(24.dp))
    Text("${progress.completedFiles} / ${progress.totalFiles}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(modifier = Modifier.height(4.dp))
    Text(progress.currentFileName, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(8.dp))
    Text(formatBytes(progress.bytesTransferred) + " / " + formatBytes(progress.totalBytes), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun DoneView(state: UiState, onRescan: () -> Unit, onResetHistory: () -> Unit) {
    val result = state.result
    Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(80.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(modifier = Modifier.height(24.dp))
    if (result != null && result.transferred > 0) {
        Text("${result.transferred} photos transferred", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        if (result.failed > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text("${result.failed} failed", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
            result.errors.take(3).forEach { err ->
                Text(err, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        Text("All caught up", fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Spacer(modifier = Modifier.height(4.dp))
        Text("No new photos since last transfer", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text("${state.totalPhotosOnCard} photos on card", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(modifier = Modifier.height(32.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = onRescan, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Rescan")
        }
        TextButton(onClick = onResetHistory) { Text("Reset History", fontSize = 12.sp) }
    }
}

@Composable
fun ErrorView(errorMessage: String?, onRetry: () -> Unit) {
    Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
    Spacer(modifier = Modifier.height(16.dp))
    Text(errorMessage ?: "Something went wrong", fontSize = 14.sp, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    Spacer(modifier = Modifier.height(16.dp))
    Button(onClick = onRetry, shape = RoundedCornerShape(16.dp)) { Text("Retry") }
    Spacer(modifier = Modifier.height(24.dp))
    SetupInstructions()
}

@Composable
fun DebugPanel(log: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Debug Log", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(log))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy log", modifier = Modifier.size(16.dp)) }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(log, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.horizontalScroll(rememberScrollState()))
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
