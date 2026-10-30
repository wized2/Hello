package com.endroid.hello.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BluetoothTransport(
    private val context: Context,
    private val onPcm: (ByteArray) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val tag = "HelloBt"
    private val serviceUuid = UUID.fromString("8b1e3a90-6f2c-4d11-9c4a-a1b2c3d4e5f6")
    private val running = AtomicBoolean(false)
    private var server: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null

    private fun adapter(): BluetoothAdapter? {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return bm?.adapter
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "hello-bt-server", isDaemon = true) {
            try {
                val ad = adapter() ?: run {
                    onStatus("Bluetooth unavailable")
                    running.set(false)
                    return@thread
                }
                if (!ad.isEnabled) {
                    onStatus("Enable Bluetooth")
                    running.set(false)
                    return@thread
                }
                val ss = ad.listenUsingInsecureRfcommWithServiceRecord("HelloPTT", serviceUuid)
                server = ss
                onStatus("Bluetooth: waiting for peer…")
                val s = ss.accept()
                socket = s
                onStatus("Bluetooth: connected")
                out = DataOutputStream(s.outputStream)
                readLoop(s)
            } catch (e: Exception) {
                Log.w(tag, "server", e)
                onStatus("Bluetooth stopped")
            } finally {
                stop()
            }
        }
    }

    private fun readLoop(s: BluetoothSocket) {
        val input = DataInputStream(s.inputStream)
        while (running.get()) {
            val len = input.readInt()
            if (len <= 0 || len > 64_000) break
            val buf = ByteArray(len)
            input.readFully(buf)
            onPcm(buf)
        }
    }

    fun sendPcm(pcm: ByteArray, len: Int) {
        try {
            val o = out ?: return
            synchronized(o) {
                o.writeInt(len)
                o.write(pcm, 0, len)
                o.flush()
            }
        } catch (e: Exception) {
            Log.w(tag, "send", e)
        }
    }

    fun stop() {
        running.set(false)
        try { out?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        out = null
        socket = null
        server = null
    }

    fun isActive(): Boolean = running.get() && socket?.isConnected == true
}
