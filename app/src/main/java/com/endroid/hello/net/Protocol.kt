package com.endroid.hello.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object Protocol {
    const val MAGIC = 0x48454C4F // "HELO"
    const val SAMPLE_RATE = 16_000
    const val AUDIO_GROUP = "239.255.42.99"
    const val AUDIO_PORT = 51515
    const val DISCOVERY_GROUP = "239.255.42.100"
    const val DISCOVERY_PORT = 51516
    const val HEADER_SIZE = 12

    fun channelKey(room: String): Int {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(room.trim().lowercase().toByteArray(Charsets.UTF_8))
        return ByteBuffer.wrap(dig, 0, 4).order(ByteOrder.BIG_ENDIAN).int
    }

    fun packAudio(channelKey: Int, seq: Int, pcm: ByteArray, offset: Int, len: Int): ByteArray {
        val out = ByteArray(HEADER_SIZE + len)
        val bb = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        bb.putInt(MAGIC)
        bb.putInt(channelKey)
        bb.putShort((seq and 0xFFFF).toShort())
        bb.putShort(len.toShort())
        System.arraycopy(pcm, offset, out, HEADER_SIZE, len)
        return out
    }

    fun unpackAudio(buf: ByteArray, length: Int): Triple<Int, Int, ByteArray>? {
        if (length < HEADER_SIZE) return null
        val bb = ByteBuffer.wrap(buf, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (bb.int != MAGIC) return null
        val key = bb.int
        val seq = bb.short.toInt() and 0xFFFF
        val len = bb.short.toInt() and 0xFFFF
        if (HEADER_SIZE + len > length) return null
        val pcm = buf.copyOfRange(HEADER_SIZE, HEADER_SIZE + len)
        return Triple(key, seq, pcm)
    }
}
