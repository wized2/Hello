package com.endroid.hello

import com.endroid.hello.net.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test
    fun packUnpackRoundTrip() {
        val pcm = ByteArray(64) { it.toByte() }
        val key = Protocol.channelKey("hello")
        val packed = Protocol.packAudio(key, 7, pcm, 0, pcm.size)
        val u = Protocol.unpackAudio(packed, packed.size)
        assertNotNull(u)
        assertEquals(key, u!!.first)
        assertEquals(7, u.second)
        assertEquals(pcm.toList(), u.third.toList())
    }

    @Test
    fun rejectsBadMagic() {
        val bad = ByteArray(20)
        assertNull(Protocol.unpackAudio(bad, bad.size))
    }

    @Test
    fun channelKeyStable() {
        assertEquals(Protocol.channelKey("Hello"), Protocol.channelKey("hello"))
    }
}
