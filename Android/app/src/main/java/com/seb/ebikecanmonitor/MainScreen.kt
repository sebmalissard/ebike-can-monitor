package com.seb.ebikecanmonitor

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.seb.ebikecanmonitor.ble.BleState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

private fun hasBlePermissions(context: Context) = BLE_PERMISSIONS.all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter

@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.connectionState.collectAsStateWithLifecycle()
    val status by vm.statusMessage.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()

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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startConnection() else vm.onPermissionDenied()
    }

    fun onConnectClick() {
        if (hasBlePermissions(context)) startConnection() else permissionLauncher.launch(BLE_PERMISSIONS)
    }

    LaunchedEffect(entries.lastOrNull()?.id, autoScroll) {
        if (autoScroll && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("eBike CAN Monitor", style = MaterialTheme.typography.headlineSmall)

            Text(
                text = status,
                style = MaterialTheme.typography.bodyLarge,
                color = if (state == BleState.Connected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = ::onConnectClick,
                    enabled = state == BleState.Disconnected,
                ) { Text("Connexion") }

                OutlinedButton(
                    onClick = vm::disconnect,
                    enabled = state != BleState.Disconnected,
                ) { Text("Déconnexion") }

                TextButton(onClick = vm::clear, enabled = entries.isNotEmpty()) { Text("Effacer") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${entries.size} messages", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Défilement auto", style = MaterialTheme.typography.labelLarge)
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
