package octometer.monitor.collect

/**
 * A thread-safe map with a fixed capacity and an LRU eviction (the size
 * of design decision D20, the brief review of 2026-10-01, MAJOR 1 and
 * MAJOR 2): the form of `IngestRateLimiter`'s own key map in
 * `kit/jvm-core`. A new key past the capacity evicts the oldest key
 * first, so an attacker who sends a flood of invented keys can never
 * grow this map without a bound, also when the service runs for a long
 * time with no restart.
 */
class LruMap<V : Any>(private val maxEntries: Int) {

    private val lock = Any()

    private val map = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>): Boolean = size > maxEntries
    }

    /** Gives the value of [key], or `null` when this map holds no such key. */
    fun get(key: String): V? = synchronized(lock) { map[key] }

    /** Stores [value] at [key], in place of a value this map already holds for it. */
    fun put(key: String, value: V) {
        synchronized(lock) { map[key] = value }
    }

    /** Reads the value of [key], or builds it with [compute] and stores it when absent. */
    fun getOrPut(key: String, compute: () -> V): V = synchronized(lock) { map.getOrPut(key, compute) }

    /** A copy of every entry of this map, for a snapshot read (`GET /api/dev/collector-counters`). */
    fun snapshot(): Map<String, V> = synchronized(lock) { LinkedHashMap(map) }

    /** The entry count of this map. A test uses this method to confirm the bound after an eviction. */
    fun size(): Int = synchronized(lock) { map.size }

    /**
     * Removes every entry (issue #299, the correction of the brief
     * review of 2026-10-01). [AppKeyCache.clear] calls this after a
     * `PUT /api/apps/{id}/routes`, so the next lookup of the app reads
     * its fresh route pattern list, inside the same request.
     */
    fun clear() {
        synchronized(lock) { map.clear() }
    }
}
