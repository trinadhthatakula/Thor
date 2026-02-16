package com.valhalla.thor.presentation.installer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.valhalla.thor.R
import com.valhalla.thor.domain.InstallState
import com.valhalla.thor.domain.repository.InstallMode
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortableInstaller(
    onDismiss: () -> Unit,
    viewModel: InstallerViewModel = koinViewModel()
) {
    val state by viewModel.installState.collectAsState(initial = InstallState.Idle)
    val availableModes by viewModel.availableModes.collectAsStateWithLifecycle()
    val installerMode by viewModel.installMode.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    // Auto-start installation process if intent is present
    LaunchedEffect(Unit) {
        val activity = context as? ComponentActivity
        val intent = activity?.intent
        if (state is InstallState.Idle && intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { uri ->
                viewModel.installFile(uri)
            }
        }
    }

    // Handle System Dialogs
    LaunchedEffect(state) {
        if (state is InstallState.UserConfirmationRequired) {
            val intent = (state as InstallState.UserConfirmationRequired).intent
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    // Launch Button Logic
    var launchIntent by remember { mutableStateOf<Intent?>(null) }
    val currentPackageName = viewModel.currentPackageName

    fun refreshLaunchState() {
        if (currentPackageName != null) {
            launchIntent = context.packageManager.getLaunchIntentForPackage(currentPackageName)
        }
    }

    DisposableEffect(currentPackageName) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_PACKAGE_ADDED ||
                    intent.action == Intent.ACTION_PACKAGE_REPLACED
                ) {
                    val installedPkg = intent.data?.schemeSpecificPart
                    if (installedPkg == currentPackageName) {
                        refreshLaunchState()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Check launch state on Success
    LaunchedEffect(state) {
        if (state is InstallState.Success) {
            refreshLaunchState()
            if (launchIntent == null) {
                delay(500)
                refreshLaunchState()
            }
        }
    }

    // The Bottom Sheet
    ModalBottomSheet(
        onDismissRequest = {
            viewModel.resetState()
            onDismiss()
        },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = null // Optional: remove handle for cleaner look
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .padding(bottom = 24.dp), // Extra padding for nav bar
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "THOR INSTALLER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )

                // Optional Close Button
                // IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) }
            }

            // Content
            when (val s = state) {
                is InstallState.Idle, is InstallState.Parsing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Analyzing Package...", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is InstallState.ReadyToInstall -> {
                    val meta = s.meta
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        if (meta.icon != null) {
                            Image(
                                bitmap = meta.icon.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(64.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = meta.label,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${meta.version} (${meta.versionCode})",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = meta.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (s.isUpdate) {
                        Text(
                            text = if (s.isDowngrade) "This will downgrade the app." else "This will update the existing app.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (s.isDowngrade) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                    val totalPermissions = remember(s.meta) { s.meta.permissions.size }
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (totalPermissions > 0) {
                            val warningMessage = if (s.shouldShowWarning()) s.getWarningMessage().orEmpty() else ""
                            Text(
                                "This package requests $totalPermissions permission${if (totalPermissions > 1) "s" else ""}. $warningMessage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp)
                            )
                        }
                        if (availableModes.size == 1) {
                            Button(
                                onClick = { viewModel.confirmInstall() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Text(
                                    s.getActionButtonText()
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                            ) {
                                var checked by remember { mutableStateOf(false) }
                                SplitButtonLayout(
                                    leadingButton = {
                                        SplitButtonDefaults.ElevatedLeadingButton(
                                            onClick = { viewModel.confirmInstall() },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        ) {
                                            Icon(
                                                painterResource(
                                                    when (installerMode) {
                                                        InstallMode.ROOT -> R.drawable.magisk_icon
                                                        InstallMode.SHIZUKU -> R.drawable.shizuku
                                                        InstallMode.DHIZUKU -> R.drawable.dhizuku // Placeholder
                                                        InstallMode.NORMAL -> R.drawable.ic_launcher_foreground // Fallback
                                                    }
                                                ),
                                                modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                                                contentDescription = "Install Mode Icon",
                                            )
                                            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                                            Text(s.getActionButtonText())
                                        }
                                    },
                                    trailingButton = {
                                        val description =
                                            "Install Options: " + availableModes.joinToString(", ") { mode ->
                                                when (mode) {
                                                    InstallMode.NORMAL -> "Normal"
                                                    InstallMode.SHIZUKU -> "Shizuku"
                                                    InstallMode.DHIZUKU -> "Dhizuku"
                                                    InstallMode.ROOT -> "Root"
                                                }
                                            }
                                        SplitButtonDefaults.ElevatedTrailingButton(
                                            checked = checked,
                                            onCheckedChange = { checked = it },
                                            modifier =
                                                Modifier.semantics {
                                                    stateDescription =
                                                        if (checked) "Expanded" else "Collapsed"
                                                    this.contentDescription = description
                                                },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primary,
                                                contentColor = MaterialTheme.colorScheme.onPrimary
                                            )
                                        ) {
                                            val rotation: Float by
                                            animateFloatAsState(
                                                targetValue = if (checked) 180f else 0f,
                                                label = "Trailing Icon Rotation",
                                            )
                                            Icon(
                                                painterResource(R.drawable.arrow_drop_down),
                                                modifier =
                                                    Modifier
                                                        .size(SplitButtonDefaults.TrailingIconSize)
                                                        .graphicsLayer {
                                                            this.rotationZ = rotation
                                                        },
                                                contentDescription = "Localized description",
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                )
                                DropdownMenu(
                                    expanded = checked,
                                    onDismissRequest = { checked = false }) {
                                    availableModes.forEach { mode ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    when (mode) {
                                                        InstallMode.NORMAL -> "Normal ${s.getActionWord()}"
                                                        InstallMode.SHIZUKU -> "${s.getActionWord()} via Shizuku"
                                                        InstallMode.DHIZUKU -> "${s.getActionWord()} via Dhizuku"
                                                        InstallMode.ROOT -> "${s.getActionWord()} with Root"
                                                    }
                                                )
                                            },
                                            onClick = {
                                                viewModel.setInstallModeAlsoInstall(mode)
                                                checked = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is InstallState.Installing -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val percentage = (s.progress * 100).toInt()
                        LinearProgressIndicator(
                            progress = { s.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Assembling: $percentage%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is InstallState.UserConfirmationRequired -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Please confirm installation in the system dialog.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                is InstallState.Success -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Installed Successfully",
                            color = Color.Green,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(Modifier.height(16.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.resetState()
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Done")
                            }

                            if (launchIntent != null) {
                                Button(
                                    onClick = {
                                        launchIntent?.let {
                                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            context.startActivity(it)
                                            viewModel.resetState()
                                            onDismiss()
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Open")
                                }
                            }
                        }
                    }
                }

                is InstallState.Error -> {
                    Text(
                        "Error: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(onClick = onDismiss) {
                        Text("Close")
                    }
                }
            }
        }
    }
}