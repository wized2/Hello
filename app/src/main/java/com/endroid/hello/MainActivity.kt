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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.endroid.hello.net.HelloTalkService
import com.endroid.hello.net.Peer

class MainActivity : ComponentActivity() {
    private var service: HelloTalkService? = null
    private var bound = false
    private val onService = mutableStateOf<HelloTalkService?>(null)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = (binder as HelloTalkService.LocalBinder).getService()
            service = s
            bound = true
            onService.value = s
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
            onService.value = null
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.all { it }) startTalkService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                HelloApp(
                    serviceState = onService.value,
                    requestStart = { ensurePermsAndStart() },
                )
            }
        }
        ensurePermsAndStart()
    }

    private fun ensurePermsAndStart() {
        val need = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
        )
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
        if (!bound) bindService(Intent(this, HelloTalkService::class.java), conn, Context.BIND_AUTO_CREATE)
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
    val prefs = remember { ctx.getSharedPreferences("hello", Context.MODE_PRIVATE) }
    var name by remember { mutableStateOf(prefs.getString("name", "") ?: "") }
    var room by remember { mutableStateOf(prefs.getString("room", "hello") ?: "hello") }
    var peers by remember { mutableStateOf<List<Peer>>(emptyList()) }
    var status by remember { mutableStateOf("Idle") }
    var talking by remember { mutableStateOf(false) }
    var directPeer by remember { mutableStateOf(prefs.getString("direct", "") ?: "") }

    DisposableEffect(serviceState) {
        val s = serviceState
        if (s == null) {
            onDispose { }
        } else {
            s.configure(name.ifBlank { "Me" }, room)
            val listener: (List<Peer>, String) -> Unit = { p, st ->
                peers = p
                status = st
            }
            s.addListener(listener)
            onDispose { s.removeListener(listener) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Hello?", fontWeight = FontWeight.SemiBold) })
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Offline push-to-talk on the same Wi‑Fi or hotspot. No account, no server.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    prefs.edit().putString("name", it).apply()
                    serviceState?.configure(it.ifBlank { "Me" }, room)
                },
                label = { Text("Display name") },
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
                label = { Text("Room code (shared with peers)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = directPeer,
                onValueChange = {
                    directPeer = it
                    prefs.edit().putString("direct", it).apply()
                    val parts = it.trim().split(":")
                    val host = parts.getOrNull(0)
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 51515
                    serviceState?.setDirectPeer(host, port)
                },
                label = { Text("Optional direct peer host:port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(status, style = MaterialTheme.typography.labelLarge)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Nearby on this room", fontWeight = FontWeight.Medium)
                    if (peers.isEmpty()) {
                        Text(
                            "No peers yet — open Hello? on another phone on the same network.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(modifier = Modifier.height(120.dp)) {
                            items(peers, key = { it.id }) { p ->
                                Text("• ${p.name}")
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(160.dp)
                    .align(Alignment.CenterHorizontally)
                    .clip(CircleShape)
                    .background(
                        if (talking) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                    .pointerInput(serviceState) {
                        detectTapGestures(
                            onPress = {
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
                        modifier = Modifier.size(48.dp),
                    )
                    Text(
                        if (talking) "Talking" else "Hold to talk",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = { serviceState?.startBluetoothHost() },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text("Optional: host Bluetooth 1:1")
            }
            if (serviceState == null) {
                Button(onClick = requestStart, modifier = Modifier.fillMaxWidth()) {
                    Text("Start listening")
                }
            }
        }
    }
}
