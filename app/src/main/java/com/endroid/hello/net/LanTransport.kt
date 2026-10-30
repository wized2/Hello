package com.endroid.hello.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Robust LAN / hotspot PTT:
 * - Discovery beacons carry local IPv4 so peers can unicast
 * - Audio: multicast + subnet broadcast + unicast to known peers
 * - Separate RX/TX sockets (Android multicast TX is unreliable on one socket)
 */
class LanTransport(
    private val context: Context,
    private val selfName: () -> String,
    private val room: () -> String,
    private val peers: PeerDirectory,
    private val onPcmFromPeer: (ByteArray) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    private val tag = "HelloLan"
    private val running = AtomicBoolean(false)
    private val talking = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    private var mcastRx: MulticastSocket? = null
    private var uniRx: DatagramSocket? = null
    private var tx: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    val selfId: String = UUID.randomUUID().toString()
    private val peerAddrs = ConcurrentHashMap<String, InetSocketAddress>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            multicastLock = wm.createMulticastLock("hello-ptt").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "mcast lock", e)
        }
        try {
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hello-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "wifi lock", e)
        }
        thread(name = "hello-mcast-rx", isDaemon = true) { multicastRxLoop() }
        thread(name = "hello-uni-rx", isDaemon = true) { unicastRxLoop() }
        thread(name = "hello-disc-rx", isDaemon = true) { discoveryRxLoop() }
        thread(name = "hello-disc-tx", isDaemon = true) { discoveryTxLoop() }
        try {
            tx = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }
        } catch (e: Exception) {
            Log.e(tag, "tx socket", e)
            onLog("TX socket failed")
        }
        onLog("LAN started · ${localIpv4() ?: "no IPv4"}")
    }

    fun stop() {
        running.set(false)
        talking.set(false)
        listOf(mcastRx, uniRx, tx).forEach { s ->
            try { s?.close() } catch (_: Exception) {}
        }
        mcastRx = null
        uniRx = null
        tx = null
        try { multicastLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        multicastLock = null
        wifiLock = null
        peerAddrs.clear()
    }

    fun setTalking(on: Boolean) {
        talking.set(on)
    }

    fun isTalking(): Boolean = talking.get()

    fun sendPcm(pcm: ByteArray, offset: Int, len: Int) {
        if (!running.get() || !talking.get()) return
        val sock = tx ?: return
        val key = Protocol.channelKey(room())
        val packet = Protocol.packAudio(key, seq.incrementAndGet(), pcm, offset, len)
        // 1) multicast
        try {
            val group = InetAddress.getByName(Protocol.AUDIO_GROUP)
            sock.send(DatagramPacket(packet, packet.size, group, Protocol.AUDIO_PORT))
        } catch (e: Exception) {
            Log.w(tag, "mcast send", e)
        }
        // 2) limited broadcast
        try {
            sock.send(
                DatagramPacket(
                    packet, packet.size,
                    InetAddress.getByName("255.255.255.255"),
                    Protocol.AUDIO_PORT,
                ),
            )
        } catch (e: Exception) {
            Log.w(tag, "bcast send", e)
        }
        // 3) subnet broadcast from local iface
        localBroadcast()?.let { bcast ->
            try {
                sock.send(DatagramPacket(packet, packet.size, bcast, Protocol.AUDIO_PORT))
            } catch (_: Exception) {}
        }
        // 4) unicast to discovered peers
        for (addr in peerAddrs.values) {
            try {
                sock.send(DatagramPacket(packet, packet.size, addr.address, addr.port))
            } catch (_: Exception) {}
        }
    }

    private fun handleAudioPacket(data: ByteArray, length: Int) {
        if (talking.get()) return
        val unpacked = Protocol.unpackAudio(data, length) ?: return
        if (unpacked.first != Protocol.channelKey(room())) return
        onPcmFromPeer(unpacked.third)
    }

    private fun multicastRxLoop() {
        try {
            val sock = MulticastSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(Protocol.AUDIO_PORT))
            sock.soTimeout = 1500
            val group = InetAddress.getByName(Protocol.AUDIO_GROUP)
            var joined = false
            preferredInterface()?.let { nif ->
                try {
                    sock.joinGroup(InetSocketAddress(group, Protocol.AUDIO_PORT), nif)
                    joined = true
                } catch (e: Exception) {
                    Log.w(tag, "join nif", e)
                }
            }
            if (!joined) {
                @Suppress("DEPRECATION")
                try {
                    sock.joinGroup(group)
                    joined = true
                } catch (e: Exception) {
                    Log.w(tag, "join legacy", e)
                }
            }
            try { sock.timeToLive = 4 } catch (_: Exception) {}
            mcastRx = sock
            onLog(if (joined) "Multicast RX ok" else "Multicast join failed — using unicast")
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    handleAudioPacket(dp.data, dp.length)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running.get()) Log.w(tag, "mcast rx", e)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "mcastRxLoop", e)
            onLog("Multicast RX error: ${e.message}")
        }
    }

    private fun unicastRxLoop() {
        try {
            // Also bind AUDIO_PORT if mcast failed; else AUDIO_PORT+2 for dual path
            val sock = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.AUDIO_PORT))
                }
            } catch (_: Exception) {
                DatagramSocket(Protocol.AUDIO_PORT + 2)
            }
            sock.soTimeout = 1500
            sock.broadcast = true
            uniRx = sock
            val buf = ByteArray(4096)
            while (running.get()) {
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    handleAudioPacket(dp.data, dp.length)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running.get()) Log.w(tag, "uni rx", e)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "unicastRxLoop", e)
        }
    }

    private fun discoveryRxLoop() {
        try {
            val sock = MulticastSocket(null)
            sock.reuseAddress = true
            sock.bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
            sock.soTimeout = 1500
            val group = InetAddress.getByName(Protocol.DISCOVERY_GROUP)
            try {
                preferredInterface()?.let {
                    sock.joinGroup(InetSocketAddress(group, Protocol.DISCOVERY_PORT), it)
                } ?: run {
                    @Suppress("DEPRECATION")
                    sock.joinGroup(group)
                }
            } catch (_: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    sock.joinGroup(group)
                } catch (_: Exception) {}
            }
            val discUni = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                    soTimeout = 1500
                    broadcast = true
                }
            } catch (_: Exception) {
                null
            }
            val buf = ByteArray(1024)
            while (running.get()) {
                // poll mcast
                try {
                    val dp = DatagramPacket(buf, buf.size)
                    sock.receive(dp)
                    handleDiscovery(String(dp.data, 0, dp.length, Charsets.UTF_8), dp.address)
                } catch (_: java.net.SocketTimeoutException) {
                } catch (e: Exception) {
                    if (running.get()) Log.w(tag, "disc mcast", e)
                }
                // poll uni
                if (discUni != null) {
                    try {
                        val dp = DatagramPacket(buf, buf.size)
                        discUni.receive(dp)
                        handleDiscovery(String(dp.data, 0, dp.length, Charsets.UTF_8), dp.address)
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (_: Exception) {}
                }
            }
            try { sock.close() } catch (_: Exception) {}
            try { discUni?.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e(tag, "discoveryRxLoop", e)
        }
    }

    private fun handleDiscovery(text: String, from: InetAddress?) {
        try {
            val json = JSONObject(text)
            if (json.optString("v") != "1") return
            if (json.optString("room") != room().trim().lowercase()) return
            val id = json.optString("id")
            if (id.isBlank() || id == selfId) return
            val name = json.optString("name")
            peers.touch(id, name)
            val ip = json.optString("ip").ifBlank { from?.hostAddress }
            val port = json.optInt("port", Protocol.AUDIO_PORT)
            if (!ip.isNullOrBlank() && !ip.contains(":")) {
                try {
                    peerAddrs[id] = InetSocketAddress(InetAddress.getByName(ip), port)
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun discoveryTxLoop() {
        val group = InetAddress.getByName(Protocol.DISCOVERY_GROUP)
        while (running.get()) {
            try {
                val sock = tx ?: DatagramSocket().also { it.broadcast = true }
                val ip = localIpv4() ?: ""
                val body = JSONObject()
                    .put("v", "1")
                    .put("id", selfId)
                    .put("name", selfName())
                    .put("room", room().trim().lowercase())
                    .put("ip", ip)
                    .put("port", Protocol.AUDIO_PORT)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(body, body.size, group, Protocol.DISCOVERY_PORT))
                try {
                    sock.send(
                        DatagramPacket(
                            body, body.size,
                            InetAddress.getByName("255.255.255.255"),
                            Protocol.DISCOVERY_PORT,
                        ),
                    )
                } catch (_: Exception) {}
                localBroadcast()?.let { b ->
                    try {
                        sock.send(DatagramPacket(body, body.size, b, Protocol.DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(tag, "discTx", e)
            }
            try {
                Thread.sleep(1500)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun preferredInterface(): NetworkInterface? = try {
        NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull { ni ->
            ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                ni.inetAddresses.toList().any { it is Inet4Address && !it.isLoopbackAddress }
        }
    } catch (_: Exception) {
        null
    }

    private fun localIpv4(): String? {
        try {
            preferredInterface()?.inetAddresses?.toList()?.forEach { a ->
                if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
            }
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.inetAddresses.toList().forEach { a ->
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun localBroadcast(): InetAddress? {
        try {
            preferredInterface()?.interfaceAddresses?.forEach { ia ->
                val b = ia.broadcast
                if (b != null) return b
            }
        } catch (_: Exception) {}
        return null
    }
}
