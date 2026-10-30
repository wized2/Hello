package com.endroid.hello.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Optional unicast UDP to a manual host:port.
 * Works across the internet only if the peer is reachable (public IP / port forward / same NAT tricks).
 * No app-operated server or database — the user supplies the address.
 */
class DirectTransport(
    private val room: () -> String,
    private val onPcm: (ByteArray) -> Unit,
) {
    private val tag = "HelloDirect"
    private val running = AtomicBoolean(false)
    private val talking = AtomicBoolean(false)
    private val seq = AtomicInteger(0)
    @Volatile private var peerHost: String? = null
    @Volatile private var peerPort: Int = Protocol.AUDIO_PORT
    private var socket: DatagramSocket? = null

    fun setPeer(host: String?, port: Int) {
        peerHost = host?.trim()?.takeIf { it.isNotEmpty() }
        peerPort = if (port in 1..65535) port else Protocol.AUDIO_PORT
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "hello-direct-rx", isDaemon = true) {
            try {
                val sock = DatagramSocket(Protocol.AUDIO_PORT + 1)
                sock.soTimeout = 1000
                socket = sock
                val buf = ByteArray(2048)
                while (running.get()) {
                    try {
                        val dp = DatagramPacket(buf, buf.size)
                        sock.receive(dp)
                        if (talking.get()) continue
                        val u = Protocol.unpackAudio(dp.data, dp.length) ?: continue
                        if (u.first != Protocol.channelKey(room())) continue
                        onPcm(u.third)
                    } catch (_: java.net.SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (running.get()) Log.w(tag, "rx", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "direct rx", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        talking.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    fun setTalking(on: Boolean) { talking.set(on) }

    fun sendPcm(pcm: ByteArray, offset: Int, len: Int) {
        if (!running.get() || !talking.get()) return
        val host = peerHost ?: return
        val sock = socket ?: return
        try {
            val packet = Protocol.packAudio(Protocol.channelKey(room()), seq.incrementAndGet(), pcm, offset, len)
            val addr = InetAddress.getByName(host)
            sock.send(DatagramPacket(packet, packet.size, addr, peerPort))
        } catch (e: Exception) {
            Log.w(tag, "send", e)
        }
    }
}
