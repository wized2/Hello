package com.endroid.hello.net

data class Peer(
    val id: String,
    val name: String,
    val lastSeenMs: Long,
)

class PeerDirectory {
    private val lock = Any()
    private val peers = LinkedHashMap<String, Peer>()

    fun touch(id: String, name: String, now: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            peers[id] = Peer(id, name.ifBlank { id.take(8) }, now)
        }
    }

    fun snapshot(ttlMs: Long = 12_000L): List<Peer> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val it = peers.entries.iterator()
            while (it.hasNext()) {
                if (now - it.next().value.lastSeenMs > ttlMs) it.remove()
            }
            return peers.values.sortedBy { it.name.lowercase() }
        }
    }
}
