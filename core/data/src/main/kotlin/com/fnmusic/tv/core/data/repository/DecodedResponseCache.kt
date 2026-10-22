package com.fnmusic.tv.core.data.repository

import java.util.LinkedHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ResponseCacheKey(
    val namespace: String,
    val kind: String,
    val businessKey: String,
    val page: Int? = null,
)

// Retains already-decoded responses so a memory hit returns them without re-parsing; the encoded
// form exists only to size entries and feed the Room persistence tier. A key's value type is fixed
// by the call-site kind that owns it ("page", "index", "lyric", "matched-lyrics").
internal class DecodedResponseCache(
    private val capacityBytes: Int,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    init {
        require(capacityBytes > 0)
    }

    private data class Entry(val value: Any, val sizeBytes: Int, val storedAtMs: Long)
    private data class Generation(val global: Long, val namespace: Long)
    private data class Flight(
        val generation: Generation,
        val deferred: Deferred<Any>,
        val persist: suspend (String) -> Unit,
        var waiters: Int,
    )

    private val mutex = Mutex()
    private val acceptanceMutex = Mutex()
    private val entries = LinkedHashMap<ResponseCacheKey, Entry>(16, 0.75f, true)
    private val flights = mutableMapOf<ResponseCacheKey, Flight>()
    private val namespaceGenerations = mutableMapOf<String, Long>()
    private var globalGeneration = 0L
    private var retainedBytes = 0

    suspend fun <T : Any> getOrFetch(
        key: ResponseCacheKey,
        encode: (T) -> String,
        isRetainedValid: (T) -> Boolean = { true },
        maxAgeMs: Long = Long.MAX_VALUE,
        persist: suspend (String) -> Unit = {},
        fetch: suspend () -> T,
    ): T {
        var created = false
        val flight = mutex.withLock {
            entries[key]?.let { entry ->
                @Suppress("UNCHECKED_CAST")
                val value = entry.value as T
                if (isRetainedValid(value) && clock() - entry.storedAtMs <= maxAgeMs) return value
                entries.remove(key)
                retainedBytes -= entry.sizeBytes
            }
            val generation = generationOf(key.namespace)
            flights[key]?.takeIf { it.generation == generation }?.also {
                it.waiters += 1
            } ?: run {
                created = true
                val deferred = scope.async(start = CoroutineStart.LAZY) { fetch() }
                Flight(generation, deferred, persist, waiters = 1).also { flights[key] = it }
            }
        }
        if (created) flight.deferred.start()
        return try {
            @Suppress("UNCHECKED_CAST")
            val value = flight.deferred.await() as T
            accept(key, flight, value, encode(value))
            value
        } finally {
            withContext(NonCancellable) { detach(key, flight) }
        }
    }

    suspend fun invalidateNamespace(namespace: String) {
        val canceled = acceptanceMutex.withLock {
            mutex.withLock {
                namespaceGenerations[namespace] = (namespaceGenerations[namespace] ?: 0L) + 1L
                val iterator = entries.iterator()
                while (iterator.hasNext()) {
                    val (key, value) = iterator.next()
                    if (key.namespace == namespace) {
                        retainedBytes -= value.sizeBytes
                        iterator.remove()
                    }
                }
                flights.entries
                    .filter { it.key.namespace == namespace }
                    .map { (key, value) ->
                        flights.remove(key)
                        value.deferred
                    }
            }
        }
        canceled.forEach { it.cancel(CancellationException("Response cache namespace was invalidated")) }
    }

    suspend fun invalidateAll() {
        val canceled = acceptanceMutex.withLock {
            mutex.withLock {
                globalGeneration += 1L
                retainedBytes = 0
                entries.clear()
                flights.values.map(Flight::deferred).also { flights.clear() }
            }
        }
        canceled.forEach { it.cancel(CancellationException("Response cache was cleared")) }
    }

    internal suspend fun retainedKeys(): List<ResponseCacheKey> = mutex.withLock { entries.keys.toList() }
    internal suspend fun retainedByteCount(): Int = mutex.withLock { retainedBytes }
    internal suspend fun waiterCount(key: ResponseCacheKey): Int = mutex.withLock { flights[key]?.waiters ?: 0 }

    private suspend fun accept(key: ResponseCacheKey, flight: Flight, value: Any, encoded: String) {
        currentCoroutineContext().ensureActive()
        acceptanceMutex.withLock {
            currentCoroutineContext().ensureActive()
            val alreadyAccepted = mutex.withLock {
                ensureAcceptable(key, flight)
                entries.containsKey(key)
            }
            if (alreadyAccepted) return@withLock

            flight.persist(encoded)
            currentCoroutineContext().ensureActive()
            mutex.withLock {
                ensureAcceptable(key, flight)
                put(key, value, encoded)
            }
        }
    }

    private fun ensureAcceptable(key: ResponseCacheKey, flight: Flight) {
        if (
            flights[key] !== flight ||
            generationOf(key.namespace) != flight.generation ||
            flight.waiters <= 0
        ) {
            throw CancellationException("Response cache result is no longer accepted")
        }
    }

    private suspend fun detach(key: ResponseCacheKey, flight: Flight) {
        val cancel = mutex.withLock {
            if (flights[key] !== flight) return@withLock false
            flight.waiters -= 1
            if (flight.waiters == 0) {
                flights.remove(key)
                flight.deferred.isActive
            } else {
                false
            }
        }
        if (cancel) flight.deferred.cancel(CancellationException("No response cache waiters remain"))
    }

    private fun generationOf(namespace: String) = Generation(
        global = globalGeneration,
        namespace = namespaceGenerations[namespace] ?: 0L,
    )

    private fun put(key: ResponseCacheKey, value: Any, encoded: String) {
        entries.remove(key)?.let { retainedBytes -= it.sizeBytes }
        val entry = Entry(value, encoded.toByteArray(Charsets.UTF_8).size, clock())
        entries[key] = entry
        retainedBytes += entry.sizeBytes
        val iterator = entries.iterator()
        while (retainedBytes > capacityBytes && iterator.hasNext()) {
            val (_, removed) = iterator.next()
            retainedBytes -= removed.sizeBytes
            iterator.remove()
        }
    }
}
