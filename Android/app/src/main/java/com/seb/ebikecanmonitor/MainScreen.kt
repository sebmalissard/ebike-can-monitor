package com.seb.ebikecanmonitor

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import kotlinx.coroutines.delay
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seb.ebikecanmonitor.ble.BleState
import com.seb.ebikecanmonitor.companion.CompanionPairing
import com.seb.ebikecanmonitor.record.Recordings
import com.seb.ebikecanmonitor.record.SmbExporter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

internal fun hasBlePermissions(context: Context) = BLE_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun hasNotificationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val autoConnect by vm.autoConnect.collectAsStateWithLifecycle()
    val status by vm.statusMessage.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val lostFrames by vm.lostFrames.collectAsStateWithLifecycle()
    val busStatus by vm.busStatus.collectAsStateWithLifecycle()
    val recordingInfo by vm.recordingInfo.collectAsStateWithLifecycle()
    val recordingError by vm.recordingError.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    var showRecordings by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    val exportState by vm.exportState.collectAsStateWithLifecycle()
    val viewer by vm.viewer.collectAsStateWithLifecycle()

    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.connect()
    }

    fun startConnection() {
        if (bluetoothAdapter(context)?.isEnabled == true) {
            vm.connect()
        } else {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }

    // Setup chain run when auto-connect is switched on: notifications -> BLE permissions.
    // Each step is asked at most once per activation, whatever the answer.
    var setupActive by remember { mutableStateOf(false) }
    var setupTick by remember { mutableIntStateOf(0) }
    val setupAsked = remember { BooleanArray(2) }

    // One-time pairing with the ESP32 (CompanionDeviceManager), started by the Pair button only.
    var associated by remember { mutableStateOf(CompanionPairing.isAssociated(context)) }
    var pairing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            if (!setupActive) startConnection()
        } else {
            vm.onPermissionDenied()
        }
        setupTick++
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { setupTick++ }

    val associationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) {
        // Pairing dialog closed (approved, cancelled or timed out): resume auto-connect
        pairing = false
        vm.endPairing()
        associated = CompanionPairing.isAssociated(context)
    }

    fun onConnectClick() {
        if (hasBlePermissions(context)) startConnection() else permissionLauncher.launch(BLE_PERMISSIONS)
    }

    fun onPairClick() {
        pairing = true
        // Pause auto-connect: a connected ESP32 stops advertising and could not be found
        vm.beginPairing()
        CompanionPairing.associate(
            context,
            onPending = { associationLauncher.launch(IntentSenderRequest.Builder(it).build()) },
            onFailure = {
                pairing = false
                vm.endPairing()
                vm.reportStatus(context.getString(R.string.status_association_failed, it))
            },
        )
    }

    // The association can be recorded slightly after the dialog closes: re-check for a few seconds
    LaunchedEffect(pairing) {
        if (pairing) return@LaunchedEffect
        repeat(6) {
            associated = CompanionPairing.isAssociated(context)
            if (associated) return@LaunchedEffect
            delay(500)
        }
    }

    LaunchedEffect(setupActive, setupTick) {
        if (!setupActive) return@LaunchedEffect
        when {
            !setupAsked[0] && !hasNotificationPermission(context) -> {
                setupAsked[0] = true
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            !setupAsked[1] && !hasBlePermissions(context) -> {
                setupAsked[1] = true
                permissionLauncher.launch(BLE_PERMISSIONS)
            }
            else -> {
                setupActive = false
                if (state == BleState.Disconnected && hasBlePermissions(context)) startConnection()
            }
        }
    }

    // Auto-connect is already on at launch: make sure the permissions are in place
    LaunchedEffect(Unit) {
        if (autoConnect) setupActive = true
    }

    LaunchedEffect(entries.lastOrNull()?.id, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    if (showRecordings) {
        RecordingsDialog(
            recordings = recordings,
            onOpen = {
                vm.openRecording(it)
                showRecordings = false
            },
            onDismiss = { showRecordings = false },
            onExport = {
                showRecordings = false
                vm.resetExport()
                showExport = true
            },
        )
    }

    if (showExport) {
        ExportDialog(
            state = exportState,
            initialUsername = remember { vm.savedSmbUsername() },
            initialPassword = remember { vm.savedSmbPassword() },
            onExport = vm::exportToSmb,
            onDismiss = {
                vm.resetExport()
                showExport = false
            },
        )
    }

    viewer?.let { current ->
        RecordingViewer(
            state = current,
            onShare = { Recordings.share(context, current.recording) },
            onClose = vm::closeRecording,
        )
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
                OutlinedButton(
                    onClick = {
                        vm.refreshRecordings()
                        showRecordings = true
                    },
                ) { Text(stringResource(R.string.app_recordings)) }
            }

            Text(
                text = status,
                style = MaterialTheme.typography.bodyLarge,
                color = if (state == BleState.Connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            recordingInfo?.let {
                Text(
                    text = stringResource(R.string.recording_to, it.fileName, it.rows),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            recordingError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = ::onConnectClick,
                    enabled = state == BleState.Disconnected && !pairing,
                ) { Text(stringResource(R.string.app_connect)) }

                OutlinedButton(
                    onClick = vm::disconnect,
                    enabled = state != BleState.Disconnected,
                ) { Text(stringResource(R.string.app_disconnect)) }

                TextButton(
                    onClick = vm::clear,
                    enabled = entries.isNotEmpty()
                ) { Text(stringResource(R.string.app_clear)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.auto_connect), style = MaterialTheme.typography.labelLarge)
                Switch(
                    checked = autoConnect,
                    onCheckedChange = { enabled ->
                        vm.setAutoConnect(enabled)
                        if (enabled) {
                            // Run the setup chain: notifications, then BLE permissions
                            setupAsked.fill(false)
                            setupActive = true
                            setupTick++
                        } else {
                            setupActive = false
                        }
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(
                        when {
                            pairing -> R.string.pairing_searching
                            associated -> R.string.pairing_ok
                            else -> R.string.pairing_missing
                        }
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (associated) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = ::onPairClick,
                    enabled = !pairing,
                ) { Text(stringResource(R.string.pairing_button)) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        pluralStringResource(R.plurals.messages_count, entries.size, entries.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (lostFrames > 0) {
                        Text(
                            pluralStringResource(
                                R.plurals.lost_frames_ble,
                                lostFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                lostFrames,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    busStatus?.takeIf { it.missed > 0 || it.overrun > 0 }?.let {
                        Text(
                            stringResource(R.string.twai_status, it.missed, it.overrun),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.auto_scroll), style = MaterialTheme.typography.labelLarge)
                    Switch(
                        checked = autoScroll,
                        onCheckedChange = { autoScroll = it },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            HorizontalDivider()

            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(entries, key = { it.id }) { entry ->
                    val timeColor = MaterialTheme.colorScheme.primary
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = timeColor)) {
                                append(timeFormatter.format(Instant.ofEpochMilli(entry.timestampMs)))
                            }
                            append("  ")
                            append(entry.text)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingsDialog(
    recordings: List<Recordings.Recording>,
    onOpen: (Recordings.Recording) -> Unit,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    val context = LocalContext.current
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.recordings_title)) },
        text = {
            if (recordings.isEmpty()) {
                Text(stringResource(R.string.recordings_empty))
            } else {
                Column {
                    Text(
                        text = stringResource(R.string.recordings_hint, Recordings.RELATIVE_PATH),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(recordings, key = { it.uri.toString() }) { recording ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(recording) }
                                    .padding(vertical = 8.dp),
                            ) {
                                Text(
                                    text = recording.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = dateFormatter.format(Instant.ofEpochMilli(recording.modifiedMs)) +
                                        "  ·  " + Formatter.formatShortFileSize(context, recording.sizeBytes),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        dismissButton = {
            TextButton(onClick = onExport, enabled = recordings.isNotEmpty()) {
                Text(stringResource(R.string.export_button))
            }
        },
    )
}

/** Asks for the Samba credentials and uploads the saved sessions to the server. */
@Composable
private fun ExportDialog(
    state: ExportState,
    initialUsername: String,
    initialPassword: String,
    onExport: (username: String, password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf(initialPassword) }
    val running = state is ExportState.Running

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.export_target, SmbExporter.DISPLAY_PATH),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.export_username)) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.export_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.export_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state) {
                    is ExportState.Idle -> Unit
                    is ExportState.Running -> {
                        if (state.total > 0) {
                            LinearProgressIndicator(
                                progress = { state.done.toFloat() / state.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                text = stringResource(R.string.export_running, state.done, state.total),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                text = stringResource(R.string.export_connecting),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    is ExportState.Success -> Text(
                        text = stringResource(R.string.export_success, state.uploaded, state.skipped),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    is ExportState.Failure -> Text(
                        text = stringResource(R.string.export_failed, state.message),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onExport(username, password) },
                enabled = !running,
            ) { Text(stringResource(R.string.export_start)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !running) {
                Text(stringResource(R.string.close))
            }
        },
    )
}

/** Full-screen viewer for a saved session. Follows the end of the file like the live log. */
@Composable
private fun RecordingViewer(
    state: ViewerState,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    // Follow the end by default only while the session is still being recorded
    var autoScroll by remember { mutableStateOf(state.live) }
    val listState = rememberLazyListState()
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }

    LaunchedEffect(state.version, autoScroll) {
        if (autoScroll && state.lines.isNotEmpty()) listState.scrollToItem(state.lines.lastIndex)
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.recording.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onShare) { Text(stringResource(R.string.app_share)) }
                    TextButton(onClick = onClose) { Text(stringResource(R.string.close)) }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(
                            pluralStringResource(
                                R.plurals.lines_count,
                                state.totalLines.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                state.totalLines,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        if (state.live) {
                            Text(
                                stringResource(R.string.viewer_live),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (state.totalLines > state.lines.size) {
                            Text(
                                stringResource(R.string.viewer_truncated, state.lines.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.auto_scroll), style = MaterialTheme.typography.labelLarge)
                        Switch(
                            checked = autoScroll,
                            onCheckedChange = { autoScroll = it },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }

                HorizontalDivider()

                LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                    items(state.lines) { line -> FileLogLine(line, timeFormatter) }
                }
            }
        }
    }
}

/**
 * One CSV row of a session file, rendered like a live log line: the leading `phone_time_ms`
 * column becomes a colored HH:mm:ss.SSS time. Header and comment lines are shown as they are.
 */
@Composable
private fun FileLogLine(line: String, timeFormatter: DateTimeFormatter) {
    val timeColor = MaterialTheme.colorScheme.primary
    val comma = line.indexOf(',')
    val timeMs = if (comma > 0) line.substring(0, comma).toLongOrNull() else null

    Text(
        text = buildAnnotatedString {
            if (timeMs != null) {
                withStyle(SpanStyle(color = timeColor)) {
                    append(timeFormatter.format(Instant.ofEpochMilli(timeMs)))
                }
                append("  ")
                append(line.substring(comma + 1))
            } else {
                append(line)
            }
        },
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 1.dp),
    )
}
