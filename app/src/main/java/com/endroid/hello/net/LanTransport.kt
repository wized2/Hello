package com.endroid.hello.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Broadcast-first PTT (inspired by classic FOSS UDP walkie-talkies).
 * Multicast is secondary — many OEM firmwares break IGMP.
 * NSD (_hello-ptt._udp) helps discovery on Android.
 * Audio is unicast to every live peer IP (+ broadcast fallback).
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
    val selfId: String = UUID.randomUUID().toString()

    private var discSocket: DatagramSocket? = null
    private var audioSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val peerAudio = ConcurrentHashMap<String, InetSocketAddress>()
    private var nsdManager: NsdManager? = null
    private var nsdRegistration: NsdManager.RegistrationListener? = null
    private var nsdDiscovery: NsdManager.DiscoveryListener? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            multicastLock = wm.createMulticastLock("hello-ptt").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hello-wifi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}

        try {
            discSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                soTimeout = 1000
            }
        } catch (e: Exception) {
            Log.e(tag, "disc bind", e)
            onLog("Discovery port busy: ${e.message}")
            try {
                discSocket = DatagramSocket().apply { broadcast = true; soTimeout = 1000 }
            } catch (_: Exception) {}
        }

        try {
            audioSocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(Protocol.AUDIO_PORT))
                soTimeout = 1000
            }
        } catch (e: Exception) {
            Log.e(tag, "audio bind", e)
            onLog("Audio port busy: ${e.message}")
            try {
                audioSocket = DatagramSocket().apply { broadcast = true; soTimeout = 1000 }
            } catch (_: Exception) {}
        }

        thread(name = "hello-disc-rx", isDaemon = true) { discoveryRxLoop() }
        thread(name = "hello-audio-rx", isDaemon = true) { audioRxLoop() }
        thread(name = "hello-disc-tx", isDaemon = true) { discoveryTxLoop() }
        startNsd()

        val ip = localIpv4()
        onLog("Ready · ${ip ?: "no IPv4"} · room ${room()}")
        Log.i(tag, "started id=$selfId ip=$ip")
    }

    fun stop() {
        running.set(false)
        talking.set(false)
        stopNsd()
        try { discSocket?.close() } catch (_: Exception) {}
        try { audioSocket?.close() } catch (_: Exception) {}
        discSocket = null
        audioSocket = null
        try { multicastLock?.release() } catch (_: Exception) {}
        try { wifiLock?.release() } catch (_: Exception) {}
        peerAudio.clear()
    }

    fun setTalking(on: Boolean) {
        talking.set(on)
    }

    fun sendPcm(pcm: ByteArray, offset: Int, len: Int) {
        if (!running.get() || !talking.get()) return
        val sock = audioSocket ?: return
        val packet = Protocol.packAudio(
            Protocol.channelKey(room()),
            seq.incrementAndGet(),
            pcm,
            offset,
            len,
        )
        // Unicast to each peer (primary — works on hotspot + most LANs)
        val targets = peerAudio.values.toList()
        if (targets.isEmpty()) {
            // Still try broadcasts so a late peer might hear us
            sendBytes(sock, packet, "255.255.255.255", Protocol.AUDIO_PORT)
            localBroadcast()?.let { sendBytes(sock, packet, it.hostAddress, Protocol.AUDIO_PORT) }
        } else {
            for (addr in targets) {
                try {
                    sock.send(DatagramPacket(packet, packet.size, addr.address, addr.port))
                } catch (e: Exception) {
                    Log.w(tag, "uni send ${addr}", e)
                }
            }
            // Plus one broadcast for devices that didn't register IP yet
            sendBytes(sock, packet, "255.255.255.255", Protocol.AUDIO_PORT)
        }
    }

    private fun sendBytes(sock: DatagramSocket, data: ByteArray, host: String?, port: Int) {
        if (host.isNullOrBlank()) return
        try {
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(host), port))
        } catch (_: Exception) {}
    }

    private fun discoveryTxLoop() {
        while (running.get()) {
            try {
                val sock = discSocket ?: break
                val body = JSONObject()
                    .put("v", "1")
                    .put("id", selfId)
                    .put("name", selfName().ifBlank { "Me" })
                    .put("room", room().trim().lowercase())
                    .put("ip", localIpv4() ?: "")
                    .put("aport", Protocol.AUDIO_PORT)
                    .toString()
                    .toByteArray(Charsets.UTF_8)

                // Global broadcast
                sendBytes(sock, body, "255.255.255.255", Protocol.DISCOVERY_PORT)
                // Subnet broadcast
                localBroadcast()?.hostAddress?.let {
                    sendBytes(sock, body, it, Protocol.DISCOVERY_PORT)
                }
                // Unicast beacon to known peers (keeps NAT mappings warm on some APs)
                for (addr in peerAudio.values) {
                    try {
                        sock.send(DatagramPacket(body, body.size, addr.address, Protocol.DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(tag, "disc tx", e)
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun discoveryRxLoop() {
        val buf = ByteArray(1024)
        while (running.get()) {
            val sock = discSocket ?: break
            try {
                val dp = DatagramPacket(buf, buf.size)
                sock.receive(dp)
                handleDiscovery(String(dp.data, 0, dp.length, Charsets.UTF_8), dp.address)
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running.get()) Log.w(tag, "disc rx", e)
            }
        }
    }

    private fun handleDiscovery(text: String, from: InetAddress?) {
        try {
            val json = JSONObject(text)
            if (json.optString("v") != "1") return
            val theirRoom = json.optString("room")
            if (theirRoom != room().trim().lowercase()) return
            val id = json.optString("id")
            if (id.isBlank() || id == selfId) return
            val name = json.optString("name").ifBlank { id.take(6) }
            peers.touch(id, name)
            val ip = json.optString("ip").ifBlank {
                from?.hostAddress?.substringBefore("%")
            }
            val port = json.optInt("aport", Protocol.AUDIO_PORT)
            if (!ip.isNullOrBlank() && !ip.contains(":")) {
                peerAudio[id] = InetSocketAddress(InetAddress.getByName(ip), port)
            } else if (from is Inet4Address) {
                peerAudio[id] = InetSocketAddress(from, port)
            }
            Log.d(tag, "peer $name @ $ip")
        } catch (e: Exception) {
            Log.w(tag, "bad discovery", e)
        }
    }

    private fun audioRxLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            val sock = audioSocket ?: break
            try {
                val dp = DatagramPacket(buf, buf.size)
                sock.receive(dp)
                if (talking.get()) continue
                val u = Protocol.unpackAudio(dp.data, dp.length) ?: continue
                if (u.first != Protocol.channelKey(room())) continue
                onPcmFromPeer(u.third)
            } catch (_: java.net.SocketTimeoutException) {
            } catch (e: Exception) {
                if (running.get()) Log.w(tag, "audio rx", e)
            }
        }
    }

    private fun startNsd() {
        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val info = NsdServiceInfo().apply {
                serviceName = "Hello-${selfId.take(8)}"
                serviceType = Protocol.NSD_SERVICE_TYPE
                port = Protocol.AUDIO_PORT
                if (Build.VERSION.SDK_INT >= 34) {
                    // attributes optional
                }
            }
            val reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(si: NsdServiceInfo) {
                    Log.i(tag, "NSD registered ${si.serviceName}")
                }
                override fun onRegistrationFailed(si: NsdServiceInfo, code: Int) {
                    Log.w(tag, "NSD reg fail $code")
                }
                override fun onServiceUnregistered(si: NsdServiceInfo) {}
                override fun onUnregistrationFailed(si: NsdServiceInfo, code: Int) {}
            }
            nsdRegistration = reg
            nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, reg)

            val disc = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {}
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType != Protocol.NSD_SERVICE_TYPE &&
                        !service.serviceType.contains("hello-ptt")
                    ) return
                    if (service.serviceName.contains(selfId.take(8))) return
                    nsdManager?.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(si: NsdServiceInfo, code: Int) {}
                            override fun onServiceResolved(si: NsdServiceInfo) {
                                val host = si.host?.hostAddress?.substringBefore("%") ?: return
                                if (host.contains(":")) return
                                val id = "nsd-$host"
                                peers.touch(id, si.serviceName.removePrefix("Hello-"))
                                peerAudio[id] = InetSocketAddress(
                                    InetAddress.getByName(host),
                                    if (si.port > 0) si.port else Protocol.AUDIO_PORT,
                                )
                                Log.i(tag, "NSD peer $host:${si.port}")
                            }
                        },
                    )
                }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, code: Int) {
                    Log.w(tag, "NSD start fail $code")
                }
                override fun onStopDiscoveryFailed(serviceType: String, code: Int) {}
            }
            nsdDiscovery = disc
            nsdManager?.discoverServices(Protocol.NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
        } catch (e: Exception) {
            Log.w(tag, "NSD", e)
        }
    }

    private fun stopNsd() {
        try {
            nsdDiscovery?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {}
        try {
            nsdRegistration?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {}
        nsdDiscovery = null
        nsdRegistration = null
        nsdManager = null
    }

    private fun localIpv4(): String? {
        try {
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
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                if (!ni.isUp || ni.isLoopback) return@forEach
                ni.interfaceAddresses.forEach { ia ->
                    ia.broadcast?.let { return it }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
