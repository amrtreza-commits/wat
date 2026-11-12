package dev.watchbridge

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.watchbridge.ble.BleManager
import dev.watchbridge.ble.QrPair
import dev.watchbridge.theme.ThemeRepository
import dev.watchbridge.theme.ThemeSpec
import dev.watchbridge.theme.WatchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = ThemeRepository(this)
        val ble = BleManager(applicationContext)
        setContent { App(repo, ble) }
    }
}

@Composable
fun App(repo: ThemeRepository, ble: BleManager) {
    val ctx = LocalContext.current
    var themes by remember { mutableStateOf(repo.loadAll()) }
    var selectedId by remember { mutableStateOf(repo.selectedId) }
    val spec = themes.firstOrNull { it.id == selectedId } ?: themes.first()
    var tab by remember { mutableIntStateOf(0) }

    WatchTheme(spec) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(tab == 0, { tab = 0 }, icon = { Text("⌚") }, label = { Text("Jam") })
                        NavigationBarItem(tab == 1, { tab = 1 }, icon = { Text("🎨") }, label = { Text("Tema") })
                    }
                }
            ) { pad ->
                Box(Modifier.padding(pad)) {
                    if (tab == 0) DeviceScreen(ble) else ThemeScreen(
                        themes = themes,
                        selectedId = selectedId,
                        onSelect = { selectedId = it; repo.selectedId = it },
                        onImport = { uri: Uri ->
                            repo.import(uri)
                                .onSuccess { t ->
                                    themes = repo.loadAll(); selectedId = t.id; repo.selectedId = t.id
                                }
                                .onFailure {
                                    Toast.makeText(ctx, "Tema tidak valid: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceScreen(ble: BleManager) {
    val s by ble.state.collectAsState()
    val clip = LocalClipboardManager.current
    val perms = if (Build.VERSION.SDK_INT >= 31)
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) ble.startScan()
    }
    val ctx = LocalContext.current
    val qrLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) QrPair.scan(ctx, { raw, mac ->
            ble.addNote("QR: $raw")
            val model = QrPair.parseModel(raw)
            if (mac != null || model != null) ble.connectByQr(mac, model)
            else Toast.makeText(ctx, "QR tidak berisi alamat Bluetooth", Toast.LENGTH_LONG).show()
        }, { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() })
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("WatchBridge", style = MaterialTheme.typography.headlineMedium) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(s.status)
                    s.battery?.let { Text("Baterai: $it%") }
                    s.heartRate?.let { Text("Detak jantung: $it bpm") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { launcher.launch(perms) }) { Text(if (s.scanning) "Mencari…" else "Cari jam") }
                OutlinedButton(onClick = { qrLauncher.launch(perms) }) { Text("Pindai QR") }
                if (s.connected != null) OutlinedButton(onClick = { ble.disconnect() }) { Text("Putuskan") }
            }
        }
        items(s.found) { f ->
            Card(Modifier.fillMaxWidth().clickable { ble.connect(f.address) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(f.name)
                    Text("${f.address} · ${f.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (s.gattDump.isNotEmpty()) {
            item {
                Column {
                    Text("Layanan GATT jam", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(s.gattDump)) }) { Text("Salin") }
                    Text(s.gattDump, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
        if (s.log.isNotEmpty()) {
            item {
                Column {
                    Text("Paket masuk dari jam", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { clip.setText(AnnotatedString(s.log.joinToString("\n"))) }) { Text("Salin") }
                    Text(s.log.takeLast(40).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun ThemeScreen(
    themes: List<ThemeSpec>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onImport: (Uri) -> Unit
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("Tema", style = MaterialTheme.typography.headlineMedium) }
        item { Button(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Impor tema (.json)") } }
        items(themes) { t ->
            Card(Modifier.fillMaxWidth().clickable { onSelect(t.id) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf(t.background, t.surface, t.primary, t.accent).forEach {
                        Box(Modifier.size(24.dp).clip(CircleShape).background(it))
                        Spacer(Modifier.width(4.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(t.name + if (t.id == selectedId) "  ✓" else "")
                }
            }
        }
    }
}
