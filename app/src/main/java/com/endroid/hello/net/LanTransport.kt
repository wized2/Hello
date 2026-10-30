package com.endroid.hello.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LanTransport(
    private val context: Context,
    private val selfName: () -> String,
    private val room: () -> String,
    private val peers: PeerDirectory,
    private val onPcmFromPeer: (ByteArray) -> Unit,
) {
    private val tag = "HelloLan"
    private val running = AtomicBoolean(false)
    private val talking = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    private var audioSocket: MulticastSocket? = null
    private var discoverySocket: MulticastSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    val selfId: String = UUID.randomUUID().toString()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("hello-ptt").apply {
            setReferenceCounted(false)
            acquire()
        }
        thread(name = "hello-audio-rx", isDaemon = true) { audioRxLoop() }
        thread(name = "hello-disc-rx", isDaemon = true) { discoveryRxLoop() }
        thread(name = "hello-disc-tx", isDaemon = true) { discoveryTxLoop() }
    }

    fun stop() {
        running.set(false)
        talking.set(false)
        try { audioSocket?.close() } catch (_: Exception) {}
        try { discoverySocket?.close() } catch (_: Exception) {}
        audioSocket = null
        discoverySocket = null
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    fun setTalking(on: Boolean) { talking.set(on) }
    fun isTalking(): Boolean = talking.get()

    fun sendPcm(pcm: ByteArray, offset: Int, len: Int) {
        if (!running.get() || !talking.get()) return
        val sock = audioSocket ?: return
        try {
            val key = Protocol.channelKey(room())
            val packet = Protocol.packAudio(key, seq.incrementAndGet(), pcm, offset, len)
            val group = InetAddress.getByName(Protocol.AUDIO_GROUP)
            sock.send(DatagramPacket(packet, packet.size, group, Protocol.AUDIO_PORT))
        } catch (e: Exception) {
            Log.w(tag, "sendPcm", e)
        }
    }

    private fun openMulticast(port: Int, groupHost: String): MulticastSocket {
        val sock = MulticastSocket(null)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(port))
        sock.soTimeout = 1000
        val group = InetAddress.getByName(groupHost)
        try {
            val nif = preferredInterface()
            if (nif != null) sock.joinGroup(InetSocketAddress(group, port), nif)
            else {
                @Suppress("DEPRECATION")
                sock.joinGroup(group)
            }
        } catch (e: Exception) {
            Log.w(tag, "joinGroup $groupHost", e)
            try {
                @Suppress("DEPRECATION")
                sock.joinGroup(group)
            } catch (_: Exception) {}
        }
        sock.timeToLive = 1
        return sock
    }

    private fun preferredInterface(): NetworkInterface? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull { ni ->
            ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                ni.inetAddresses.toList().any { !it.isLoopbackAddress && it.hostAddress?.contains(":") != true }
        }
    } catch (_: Exception) { null }

    private fun audioRxLoop() {
        try {
            val sock = openMulticast(Protocol.AUDIO_PORT, Protocol.AUDIO_GROUP)
            audioSocket = sock
            val buf = ByteArray(2048)
            while (running.get()) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    if (talking.get()) continue
                    val unpacked = Protocol.unpackAudio(dp.data, dp.length) ?: continue
                    if (unpacked.first != Protocol.channelKey(room())) continue
                    onPcmFromPeer(unpacked.third)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running.get()) Log.w(tag, "audioRx", e)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "audioRxLoop", e)
        }
    }

    private fun discoveryRxLoop() {
        try {
            val sock = openMulticast(Protocol.DISCOVERY_PORT, Protocol.DISCOVERY_GROUP)
            discoverySocket = sock
            val buf = ByteArray(512)
            while (running.get()) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    val text = String(dp.data, 0, dp.length, Charsets.UTF_8)
                    val json = JSONObject(text)
                    if (json.optString("v") != "1") continue
                    if (json.optString("room") != room().trim().lowercase()) continue
                    val id = json.optString("id")
                    if (id.isBlank() || id == selfId) continue
                    peers.touch(id, json.optString("name"))
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running.get()) Log.w(tag, "discRx", e)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "discoveryRxLoop", e)
        }
    }

    private fun discoveryTxLoop() {
        val group = InetAddress.getByName(Protocol.DISCOVERY_GROUP)
        while (running.get()) {
            try {
                val sock = discoverySocket
                if (sock != null && !sock.isClosed) {
                    val body = JSONObject()
                        .put("v", "1")
                        .put("id", selfId)
                        .put("name", selfName())
                        .put("room", room().trim().lowercase())
                        .toString()
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    sock.send(DatagramPacket(bytes, bytes.size, group, Protocol.DISCOVERY_PORT))
                }
            } catch (e: Exception) {
                Log.w(tag, "discTx", e)
            }
            try { Thread.sleep(2500) } catch (_: InterruptedException) { break }
        }
    }
}
