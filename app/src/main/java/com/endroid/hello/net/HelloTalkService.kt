package com.endroid.hello.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.endroid.hello.MainActivity
import com.endroid.hello.R
import java.util.concurrent.CopyOnWriteArrayList

class HelloTalkService : Service() {
    inner class LocalBinder : Binder() {
        fun getService(): HelloTalkService = this@HelloTalkService
    }

    private val binder = LocalBinder()
    private val peers = PeerDirectory()
    private var lan: LanTransport? = null
    private var bt: BluetoothTransport? = null
    private var direct: DirectTransport? = null
    private var audio: AudioEngine? = null
    private var displayName: String = "Me"
    private var room: String = "hello"
    private val listeners = CopyOnWriteArrayList<(List<Peer>, String) -> Unit>()
    @Volatile private var statusLine: String = "Starting…"

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        audio = AudioEngine(this) { buf, n ->
            lan?.sendPcm(buf, 0, n)
            direct?.sendPcm(buf, 0, n)
            if (bt?.isActive() == true) bt?.sendPcm(buf, n)
        }
        lan = LanTransport(
            this,
            selfName = { displayName },
            room = { room },
            peers = peers,
            onPcmFromPeer = { pcm -> audio?.playPcm(pcm) },
            onLog = { s ->
                statusLine = s
                notifyListeners()
            },
        ).also { it.start() }
        direct = DirectTransport(room = { room }, onPcm = { pcm -> audio?.playPcm(pcm) }).also { it.start() }
        bt = BluetoothTransport(
            this,
            onPcm = { pcm -> audio?.playPcm(pcm) },
            onStatus = { s ->
                statusLine = s
                notifyListeners()
            },
        )
        statusLine = "Listening on LAN / hotspot"
        notifyListeners()
        // peer list refresh ticker
        Thread {
            while (true) {
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                notifyListeners()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    override fun onDestroy() {
        lan?.stop()
        direct?.stop()
        bt?.stop()
        audio?.release()
        lan = null
        direct = null
        bt = null
        audio = null
        super.onDestroy()
    }

    fun configure(name: String, roomCode: String) {
        displayName = name.ifBlank { "Me" }
        room = roomCode.ifBlank { "hello" }
    }

    fun setTalking(on: Boolean) {
        lan?.setTalking(on)
        direct?.setTalking(on)
        if (on) audio?.startCapture() else audio?.stopCapture()
        statusLine = if (on) "Transmitting…" else "Listening on LAN / hotspot"
        notifyListeners()
    }

    fun setDirectPeer(host: String?, port: Int) {
        direct?.setPeer(host, port)
        if (!host.isNullOrBlank()) {
            statusLine = "Direct peer $host:$port"
            notifyListeners()
        }
    }

    fun startBluetoothHost() {
        bt?.startServer()
    }

    fun stopBluetooth() {
        bt?.stop()
        statusLine = "Listening on LAN / hotspot"
        notifyListeners()
    }

    fun addListener(l: (List<Peer>, String) -> Unit) {
        listeners.add(l)
        l(peers.snapshot(), statusLine)
    }

    fun removeListener(l: (List<Peer>, String) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyListeners() {
        val snap = peers.snapshot()
        val st = statusLine
        listeners.forEach { it(snap, st) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotif(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val CHANNEL = "hello_talk"
        const val NOTIF_ID = 71

        fun start(context: Context) {
            val i = Intent(context, HelloTalkService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HelloTalkService::class.java))
        }
    }
}
