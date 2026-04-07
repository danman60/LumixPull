package com.lumixpull.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Lumix-inspired color palette — warm amber + deep charcoal
private val LumixPrimary = Color(0xFFE8A838)
private val LumixPrimaryDark = Color(0xFFCC8B1F)
private val LumixOnPrimary = Color(0xFF1A1200)
private val LumixSurface = Color(0xFF121212)
private val LumixSurfaceVariant = Color(0xFF1E1E1E)
private val LumixSurfaceElevated = Color(0xFF252525)
private val LumixOnSurface = Color(0xFFE8E8E8)
private val LumixOnSurfaceVariant = Color(0xFF9E9E9E)
private val LumixError = Color(0xFFEF5350)
private val LumixSuccess = Color(0xFF66BB6A)
private val LumixTertiary = Color(0xFF80CBC4)

private val LumixColorScheme = darkColorScheme(
    primary = LumixPrimary,
    onPrimary = LumixOnPrimary,
    secondary = LumixPrimaryDark,
    tertiary = LumixTertiary,
    background = LumixSurface,
    surface = LumixSurface,
    surfaceVariant = LumixSurfaceVariant,
    onBackground = LumixOnSurface,
    onSurface = LumixOnSurface,
    onSurfaceVariant = LumixOnSurfaceVariant,
    error = LumixError
)

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val openDocumentTree = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.onSafFolderSelected(uri)
        }
    }

    fun launchFolderPicker() {
        openDocumentTree.launch(null)
    }

    fun launchStoragePermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val crashLog = LumixPullApp.getLastCrash(this)
        if (crashLog != null) {
            viewModel.setCrashLog(crashLog)
            LumixPullApp.clearCrash(this)
        }

        setContent {
            MaterialTheme(colorScheme = LumixColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by viewModel.state.collectAsState()

                    LaunchedEffect(Unit) {
                        viewModel.autoStart()
                    }

                    LumixPullScreen(
                        state = state,
                        onRetry = { viewModel.detectAndConnect() },
                        onTransfer = { viewModel.transfer() },
                        onTestTransfer = { viewModel.testTransfer() },
                        onResetHistory = viewModel::resetHistory,
                        onSelectVolume = { viewModel.selectVolume(it) },
                        onPickFolder = { launchFolderPicker() },
                        onGrantPermission = { launchStoragePermission() }
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
    onResetHistory: () -> Unit,
    onTestTransfer: () -> Unit = {},
    onSelectVolume: (String) -> Unit = {},
    onPickFolder: () -> Unit = {},
    onGrantPermission: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header
        AppHeader()
        Spacer(modifier = Modifier.height(28.dp))

        // State content with crossfade
        AnimatedContent(
            targetState = state.transferState,
            transitionSpec = {
                fadeIn(tween(300)) togetherWith fadeOut(tween(200))
            },
            label = "state-transition"
        ) { transferState ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when (transferState) {
                    TransferState.IDLE -> IdleView(onRetry)
                    TransferState.AWAITING_PERMISSION -> PermissionView()
                    TransferState.CONNECTING -> ConnectingView()
                    TransferState.SCANNING -> ScanningView()
                    TransferState.PICK_VOLUME -> VolumePickerView(state, onSelectVolume, onPickFolder, onGrantPermission, onRetry)
                    TransferState.READY -> ReadyView(state, onTransfer, onTestTransfer)
                    TransferState.TRANSFERRING -> TransferringView(state.progress)
                    TransferState.DONE -> DoneView(state, onRetry, onResetHistory)
                    TransferState.ERROR -> ErrorView(state.errorMessage, onRetry)
                }
            }
        }

        if (state.debugLog.isNotBlank()) {
            Spacer(modifier = Modifier.height(28.dp))
            DebugPanel(state.debugLog)
        }
    }
}

@Composable
fun AppHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = LumixPrimary
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                "LumixPull",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = LumixOnSurface,
                letterSpacing = (-0.5).sp
            )
        }
    }
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        "S5II USB-C Photo Transfer",
        fontSize = 13.sp,
        color = LumixOnSurfaceVariant,
        letterSpacing = 1.sp
    )
}

// ─── Idle / Setup ───

@Composable
fun IdleView(onConnect: () -> Unit) {
    SetupInstructions()
    Spacer(modifier = Modifier.height(20.dp))
    Button(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = LumixPrimary,
            contentColor = LumixOnPrimary
        )
    ) {
        Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Connect Camera", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SetupInstructions() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LumixSurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                "Quick Setup",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = LumixPrimary,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(14.dp))

            InstructionStep(1, "Camera USB Mode", "Menu > Setup > USB > PC(Tether)", Icons.Outlined.Settings)
            Spacer(modifier = Modifier.height(10.dp))
            InstructionStep(2, "Connect Cable", "USB-C to USB-C, camera to phone", Icons.Outlined.Cable)
            Spacer(modifier = Modifier.height(10.dp))
            InstructionStep(3, "Allow Access", "Tap Allow on the permission popup", Icons.Outlined.Security)
            Spacer(modifier = Modifier.height(10.dp))
            InstructionStep(4, "Transfer", "Tap Transfer or Test with 3 files", Icons.Outlined.Sync)
            Spacer(modifier = Modifier.height(10.dp))
            InstructionStep(5, "Google Photos", "Library > Photos on device > Lumix", Icons.Outlined.PhotoLibrary)
        }
    }
}

@Composable
fun InstructionStep(number: Int, title: String, detail: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Numbered circle
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(LumixPrimary.copy(alpha = 0.15f))
        ) {
            Text(
                "$number",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = LumixPrimary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LumixOnSurface)
            Text(detail, fontSize = 11.sp, color = LumixOnSurfaceVariant, lineHeight = 15.sp)
        }
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp).alpha(0.4f),
            tint = LumixOnSurfaceVariant
        )
    }
}

// ─── Loading States ───

@Composable
fun PermissionView() {
    Spacer(modifier = Modifier.height(40.dp))
    PulsingIcon(Icons.Outlined.Security, LumixPrimary)
    Spacer(modifier = Modifier.height(20.dp))
    Text("Waiting for USB Permission", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(6.dp))
    Text("Tap Allow on the system popup", fontSize = 13.sp, color = LumixOnSurfaceVariant)
}

@Composable
fun ConnectingView() {
    Spacer(modifier = Modifier.height(40.dp))
    PulsingIcon(Icons.Default.CameraAlt, LumixPrimary)
    Spacer(modifier = Modifier.height(20.dp))
    Text("Connecting to Camera", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(6.dp))
    Text("Establishing MTP session...", fontSize = 13.sp, color = LumixOnSurfaceVariant)
}

@Composable
fun ScanningView() {
    Spacer(modifier = Modifier.height(40.dp))
    PulsingIcon(Icons.Default.PhotoLibrary, LumixPrimary)
    Spacer(modifier = Modifier.height(20.dp))
    Text("Scanning Files", fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Spacer(modifier = Modifier.height(6.dp))
    Text("Finding photos and videos on card...", fontSize = 13.sp, color = LumixOnSurfaceVariant)
}

@Composable
fun PulsingIcon(icon: ImageVector, tint: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-alpha"
    )
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse-scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size((72 * scale).dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.08f * alpha))
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size((36 * scale).dp).alpha(alpha),
            tint = tint
        )
    }
}

// ─── Ready ───

@Composable
fun VolumePickerView(state: UiState, onSelectVolume: (String) -> Unit, onPickFolder: () -> Unit, onGrantPermission: () -> Unit, onRetry: () -> Unit) {
    // Device header
    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(48.dp), tint = LumixPrimary)
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        state.cameraName?.substringBefore("-") ?: "Device",
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(4.dp))

    // Permission request
    if (state.needsStoragePermission) {
        Text("File access permission needed", fontSize = 13.sp, color = LumixOnSurfaceVariant)
        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = LumixSurfaceElevated),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text("One-time setup", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Grant \"All files access\" so LumixPull can read USB devices directly.", fontSize = 12.sp, color = LumixOnSurfaceVariant, lineHeight = 16.sp)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onGrantPermission,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LumixPrimary, contentColor = LumixOnPrimary)
        ) { Text("Grant Permission", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) { Text("Retry After Granting", fontSize = 13.sp) }
        return
    }

    Text("Select storage to transfer from", fontSize = 13.sp, color = LumixOnSurfaceVariant)
    Spacer(modifier = Modifier.height(20.dp))

    for (volume in state.availableVolumes) {
        val icon = when {
            volume.name.contains("SD", ignoreCase = true) -> Icons.Default.PhotoLibrary
            volume.name.contains("Internal", ignoreCase = true) -> Icons.Default.Usb
            else -> Icons.Default.PhotoLibrary
        }
        val subtitle = when {
            volume.accessible && volume.fileCount > 0 -> "${volume.fileCount} media files"
            volume.accessible -> "No media files found"
            else -> "Tap to grant access"
        }

        Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
            colors = CardDefaults.cardColors(containerColor = LumixSurfaceElevated),
            shape = RoundedCornerShape(14.dp),
            onClick = {
                if (volume.accessible && volume.fileCount > 0) {
                    onSelectVolume(volume.path)
                } else {
                    onPickFolder()
                }
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LumixPrimary.copy(alpha = 0.12f))
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = LumixPrimary)
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(volume.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, fontSize = 12.sp, color = LumixOnSurfaceVariant)
                }
                if (!volume.accessible) {
                    Icon(
                        Icons.Default.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = LumixPrimary.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }

    if (state.availableVolumes.isEmpty()) {
        Text(
            "No storage volumes detected.\nUse the button below to browse manually.",
            fontSize = 13.sp,
            color = LumixOnSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onPickFolder,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LumixPrimary, contentColor = LumixOnPrimary)
        ) { Text("Browse Storage", fontSize = 14.sp) }
    }
}

@Composable
fun ReadyView(state: UiState, onTransfer: () -> Unit, onTestTransfer: () -> Unit = {}) {
    // Photo count card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LumixSurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = LumixPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Animated count
            val count by animateIntAsState(
                targetValue = state.newPhotoCount,
                animationSpec = tween(600, easing = EaseOutCubic),
                label = "count"
            )
            Text(
                "$count",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = LumixPrimary,
                letterSpacing = (-1).sp
            )
            Text(
                "files ready to transfer",
                fontSize = 13.sp,
                color = LumixOnSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Primary transfer button
    Button(
        onClick = onTransfer,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LumixPrimary, contentColor = LumixOnPrimary)
    ) {
        Icon(Icons.Outlined.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text("Transfer All", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }

    Spacer(modifier = Modifier.height(10.dp))

    Spacer(modifier = Modifier.height(10.dp))
    OutlinedButton(
        onClick = onTestTransfer,
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = LumixTertiary)
    ) { Text("Test (3 files)", fontSize = 13.sp) }
}

// ─── Transferring ───

@Composable
fun TransferringView(progress: TransferProgress?) {
    if (progress == null) {
        Spacer(modifier = Modifier.height(40.dp))
        PulsingIcon(Icons.Outlined.Sync, LumixPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        Text("Starting transfer...", fontSize = 14.sp, color = LumixOnSurfaceVariant)
        return
    }

    val fraction = if (progress.totalFiles > 0) {
        progress.completedFiles.toFloat() / progress.totalFiles.toFloat()
    } else 0f

    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(400, easing = EaseOutCubic),
        label = "progress"
    )

    Spacer(modifier = Modifier.height(20.dp))

    // Progress card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LumixSurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            // Large progress ring
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { animatedFraction },
                    modifier = Modifier.size(100.dp),
                    strokeWidth = 8.dp,
                    color = LumixPrimary,
                    trackColor = LumixSurfaceVariant
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${progress.completedFiles}",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = LumixPrimary
                    )
                    Text(
                        "of ${progress.totalFiles}",
                        fontSize = 11.sp,
                        color = LumixOnSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Current file
            Text(
                progress.currentFileName,
                fontSize = 12.sp,
                color = LumixOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (progress.totalBytes > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "${formatBytes(progress.bytesTransferred)} / ${formatBytes(progress.totalBytes)}",
                    fontSize = 11.sp,
                    color = LumixOnSurfaceVariant.copy(alpha = 0.7f)
                )
            }

            // Linear progress bar
            Spacer(modifier = Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = LumixPrimary,
                trackColor = LumixSurfaceVariant
            )
        }
    }
}

// ─── Done ───

@Composable
fun DoneView(state: UiState, onRescan: () -> Unit, onResetHistory: () -> Unit) {
    val result = state.result

    Spacer(modifier = Modifier.height(20.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LumixSurfaceElevated),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            val iconTint = if (result != null && result.failed > 0) LumixPrimary else LumixSuccess

            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = iconTint
            )
            Spacer(modifier = Modifier.height(14.dp))

            if (result != null && result.transferred > 0) {
                Text(
                    "${result.transferred} files transferred",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (result.failed > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("${result.failed} failed", fontSize = 13.sp, color = LumixError)
                    result.errors.take(3).forEach { err ->
                        Text(err, fontSize = 10.sp, color = LumixError.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Text("All caught up", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("No new files to transfer", fontSize = 13.sp, color = LumixOnSurfaceVariant)
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = onRescan,
            modifier = Modifier.weight(1f).height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Rescan", fontSize = 13.sp)
        }
        TextButton(
            onClick = onResetHistory,
            modifier = Modifier.height(44.dp)
        ) { Text("Reset History", fontSize = 12.sp, color = LumixOnSurfaceVariant) }
    }
}

// ─── Error ───

@Composable
fun ErrorView(errorMessage: String?, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LumixError.copy(alpha = 0.08f)),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            Icon(Icons.Default.Error, contentDescription = null, modifier = Modifier.size(48.dp), tint = LumixError)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                errorMessage ?: "Something went wrong",
                fontSize = 13.sp,
                color = LumixError,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    Button(
        onClick = onRetry,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = LumixPrimary, contentColor = LumixOnPrimary)
    ) { Text("Retry Connection", fontSize = 14.sp, fontWeight = FontWeight.Medium) }

    Spacer(modifier = Modifier.height(20.dp))
    SetupInstructions()
}

// ─── Debug Panel ───

@Composable
fun DebugPanel(log: String) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = LumixSurfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        if (expanded) "Debug Log (tap to hide)" else "Debug Log (tap to show)",
                        fontSize = 11.sp,
                        color = LumixOnSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(log))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp), tint = LumixOnSurfaceVariant)
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(200)) + fadeOut(tween(150))
            ) {
                Text(
                    log,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    color = LumixOnSurfaceVariant.copy(alpha = 0.8f),
                    lineHeight = 13.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .horizontalScroll(rememberScrollState())
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
