package com.endroid.hello

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.endroid.hello.net.HelloTalkService
import com.endroid.hello.net.Peer

class MainActivity : ComponentActivity() {
    private var bound = false
    private val onService = mutableStateOf<HelloTalkService?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            bound = true
            onService.value = (binder as HelloTalkService.LocalBinder).getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            onService.value = null
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) startTalkService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Keep screen on while talking UI is open
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    HelloApp(
                        serviceState = onService.value,
                        requestStart = { ensurePermsAndStart() },
                    )
                }
            }
        }
        ensurePermsAndStart()
    }

    private fun ensurePermsAndStart() {
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_CONNECT
            need += Manifest.permission.BLUETOOTH_ADVERTISE
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startTalkService()
        else permLauncher.launch(missing.toTypedArray())
    }

    private fun startTalkService() {
        HelloTalkService.start(this)
        if (!bound) {
            bindService(
                Intent(this, HelloTalkService::class.java),
                conn,
                Context.BIND_AUTO_CREATE,
            )
        }
    }

    override fun onDestroy() {
        if (bound) {
            unbindService(conn)
            bound = false
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelloApp(serviceState: HelloTalkService?, requestStart: () -> Unit) {
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    val prefs = remember { ctx.getSharedPreferences("hello", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var room by remember { mutableStateOf(prefs.getString("room", "hello") ?: "hello") }
    var directPeer by remember { mutableStateOf(prefs.getString("direct", "") ?: "") }
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var status by remember { mutableStateOf("Starting…") }
    var talking by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    DisposableEffect(serviceState) {
        val s = serviceState
        if (s == null) {
            onDispose { }
        } else {
            s.configure(name.ifBlank { "Me" }, room)
            applyDirect(s, directPeer)
            val listener: (List<Peer>, String) -> Unit = { p, st ->
                peers = p
                status = st
            }
            s.addListener(listener)
            onDispose { s.removeListener(listener) }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        // Header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Hello?", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text(
                    status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                )
            }
            IconButton(onClick = { showSettings = !showSettings }) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }

        // Settings (collapsed by default so PTT fits small screens)
        if (showSettings) {
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        prefs.edit().putString("name", it).apply()
                        serviceState?.configure(it.ifBlank { "Me" }, room)
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = room,
                    onValueChange = {
                        room = it
                        prefs.edit().putString("room", it).apply()
                        serviceState?.configure(name.ifBlank { "Me" }, it)
                    },
                    label = { Text("Room") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = directPeer,
                    onValueChange = {
                        directPeer = it
                        prefs.edit().putString("direct", it).apply()
                        serviceState?.let { s -> applyDirect(s, it) }
                    },
                    label = { Text("Peer IP (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Both phones: same Wi‑Fi/hotspot + same room. Or type the other phone’s IP.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (serviceState == null) {
                    Button(onClick = requestStart, modifier = Modifier.fillMaxWidth()) {
                        Text("Start")
                    }
                }
            }
        } else {
            // Peers fill middle when settings hidden
            Card(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                ),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        "Room “$room” · ${peers.size} nearby",
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (peers.isEmpty()) {
                        Text(
                            "No peers yet.\n• Same Wi‑Fi or hotspot\n• Same room code\n• Open Hello? on the other phone\n• Or set Peer IP in settings ⚙",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        peers.forEach { p ->
                            Text("● ${p.name}", fontSize = 16.sp)
                        }
                    }
                }
            }
        }

        // PTT — always at bottom, above nav + keyboard
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(if (showSettings) 88.dp else 128.dp)
                    .clip(CircleShape)
                    .background(
                        if (talking) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                    .pointerInput(serviceState) {
                        detectTapGestures(
                            onPress = {
                                focus.clearFocus()
                                showSettings = false
                                serviceState?.setTalking(true)
                                talking = true
                                try {
                                    awaitRelease()
                                } finally {
                                    serviceState?.setTalking(false)
                                    talking = false
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Hold to talk",
                        tint = Color.White,
                        modifier = Modifier.size(if (showSettings) 32.dp else 44.dp),
                    )
                    Text(
                        if (talking) "ON AIR" else "HOLD",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                    )
                }
            }
            Text(
                if (talking) "Release to stop" else "Hold to talk",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun applyDirect(s: HelloTalkService, raw: String) {
    val t = raw.trim()
    if (t.isEmpty()) {
        s.setDirectPeer(null, 41235)
        return
    }
    val parts = t.split(":")
    val host = parts[0].trim()
    val port = parts.getOrNull(1)?.toIntOrNull() ?: 41235
    s.setDirectPeer(host, port)
}
