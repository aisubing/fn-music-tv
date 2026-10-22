package com.fnmusic.tv.core.data.repository

import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DecodedResponseCacheTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
    }

    @Test fun `sequential and concurrent reads share one upstream result`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("server:user", "index", "playlists")
        val calls = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val first = async {
            cache.getOrFetch(key, encode = { value: String -> value }) {
                calls.incrementAndGet()
                release.await()
                "payload"
            }
        }
        awaitWaiters(cache, key, 1)
        val second = async { cache.getOrFetch(key, encode = { value: String -> value }) { mustNotRun<String>() } }
        awaitWaiters(cache, key, 2)
        release.complete(Unit)

        assertEquals("payload", first.await())
        assertEquals("payload", second.await())
        assertEquals("payload", cache.getOrFetch(key, encode = { value: String -> value }) { mustNotRun<String>() })
        assertEquals(1, calls.get())
    }

    @Test fun `one canceled waiter does not cancel a remaining waiter`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("server:user", "page", "tracks", 1)
        val release = CompletableDeferred<Unit>()
        val upstreamCanceled = CompletableDeferred<Unit>()
        val persisted = AtomicInteger()

        val first = async {
            cache.getOrFetch(
                key = key,
                encode = { value: String -> value },
                persist = { persisted.incrementAndGet() },
            ) {
                try {
                    release.await()
                    "shared"
                } finally {
                    if (!release.isCompleted) upstreamCanceled.complete(Unit)
                }
            }
        }
        awaitWaiters(cache, key, 1)
        val second = async { cache.getOrFetch(key, encode = { value: String -> value }) { mustNotRun<String>() } }
        awaitWaiters(cache, key, 2)

        first.cancelAndJoin()
        assertFalse(upstreamCanceled.isCompleted)
        release.complete(Unit)
        assertEquals("shared", second.await())
        assertEquals(1, persisted.get())
    }

    @Test fun `last canceled waiter cancels upstream work`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("server:user", "index", "artists")
        val started = CompletableDeferred<Unit>()
        val canceled = CompletableDeferred<Unit>()
        val waiter = async {
            cache.getOrFetch(key, encode = { value: String -> value }) {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    canceled.complete(Unit)
                }
            }
        }
        awaitWaiters(cache, key, 1)
        started.await()

        waiter.cancelAndJoin()

        canceled.await()
        assertEquals(0, cache.waiterCount(key))
    }

    @Test fun `last canceled waiter cannot persist or populate a non cancellable late result`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("server:user", "lyric", "track")
        val fetchStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val persisted = AtomicInteger()
        val waiter = async {
            cache.getOrFetch(
                key = key,
                encode = { value: String -> value },
                persist = { persisted.incrementAndGet() },
            ) {
                fetchStarted.complete(Unit)
                withContext(NonCancellable) { release.await() }
                "late"
            }
        }
        fetchStarted.await()

        waiter.cancel()
        release.complete(Unit)
        waiter.join()

        assertEquals(0, persisted.get())
        assertTrue(cache.retainedKeys().isEmpty())
        assertEquals("fresh", cache.getOrFetch(key, encode = { value: String -> value }) { "fresh" })
    }

    @Test fun `byte capacity uses access ordered eviction`() = runBlocking {
        val cache = DecodedResponseCache(6, scope)
        val first = ResponseCacheKey("n", "index", "first")
        val second = ResponseCacheKey("n", "index", "second")
        val third = ResponseCacheKey("n", "index", "third")
        cache.getOrFetch(first, encode = { value: String -> value }) { "aa" }
        cache.getOrFetch(second, encode = { value: String -> value }) { "bb" }
        cache.getOrFetch(first, encode = { value: String -> value }) { mustNotRun<String>() }

        cache.getOrFetch(third, encode = { value: String -> value }) { "ccc" }

        assertEquals(listOf(first, third), cache.retainedKeys())
        assertEquals(5, cache.retainedByteCount())
        assertEquals("new-second", cache.getOrFetch(second, encode = { value: String -> value }) { "new-second" })
    }

    @Test fun `failed fetch is removed and can be retried`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("n", "index", "album")
        assertThrows(IOException::class.java) {
            runBlocking { cache.getOrFetch(key, encode = { value: String -> value }) { throw IOException("offline") } }
        }

        assertEquals("recovered", cache.getOrFetch(key, encode = { value: String -> value }) { "recovered" })
    }

    @Test fun `stale retained value is removed and fetched again`() = runBlocking {
        var time = 1_000_000L
        val cache = DecodedResponseCache(1024, scope, clock = { time })
        val key = ResponseCacheKey("n", "page", "tracks", 1)
        cache.getOrFetch(key, encode = { value: String -> value }) { "stale" }

        time += 11 * 60 * 1000L
        val refreshed = cache.getOrFetch(
            key = key,
            encode = { value: String -> value },
            maxAgeMs = 10 * 60 * 1000L,
        ) { "fresh" }

        assertEquals("fresh", refreshed)
        assertEquals(
            "fresh",
            cache.getOrFetch(
                key = key,
                encode = { value: String -> value },
                maxAgeMs = 10 * 60 * 1000L,
            ) { mustNotRun<String>() },
        )
    }

    @Test fun `invalid retained value is removed and fetched again`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("n", "matched-lyrics", "track:fingerprint")
        cache.getOrFetch(key, encode = { value: String -> value }) { "expired" }

        val value = cache.getOrFetch(
            key = key,
            encode = { value: String -> value },
            isRetainedValid = { it != "expired" },
        ) { "fresh" }

        assertEquals("fresh", value)
        assertEquals("fresh", cache.getOrFetch(key, encode = { value: String -> value }) { mustNotRun<String>() })
    }

    @Test fun `namespace invalidation rejects a late non cancellable result`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("old", "index", "playlists")
        val release = CompletableDeferred<Unit>()
        val persisted = AtomicInteger()
        val waiter = async {
            cache.getOrFetch(
                key = key,
                encode = { value: String -> value },
                persist = { persisted.incrementAndGet() },
            ) {
                withContext(NonCancellable) { release.await() }
                "late"
            }
        }
        awaitWaiters(cache, key, 1)

        cache.invalidateNamespace("old")
        release.complete(Unit)
        waiter.cancelAndJoin()

        assertTrue(cache.retainedKeys().isEmpty())
        assertEquals(0, persisted.get())
        assertEquals("fresh", cache.getOrFetch(key, encode = { value: String -> value }) { "fresh" })
    }

    @Test fun `invalidate is ordered after an accepted persist and removes its memory result`() = runBlocking {
        val cache = DecodedResponseCache(1024, scope)
        val key = ResponseCacheKey("old", "index", "album")
        val persistStarted = CompletableDeferred<Unit>()
        val releasePersist = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val waiter = async {
            cache.getOrFetch(
                key = key,
                encode = { value: String -> value },
                persist = {
                    persistStarted.complete(Unit)
                    releasePersist.await()
                    events += "persist"
                },
            ) { "payload" }
        }
        persistStarted.await()
        val clear = async {
            cache.invalidateNamespace("old")
            events += "clear"
        }
        yield()
        assertFalse(clear.isCompleted)

        releasePersist.complete(Unit)
        waiter.await()
        clear.await()

        assertEquals(listOf("persist", "clear"), events)
        assertTrue(cache.retainedKeys().isEmpty())
    }

    private suspend fun awaitWaiters(
        cache: DecodedResponseCache,
        key: ResponseCacheKey,
        expected: Int,
    ) {
        repeat(10_000) {
            if (cache.waiterCount(key) == expected) return
            yield()
        }
        error("Timed out waiting for $expected cache waiters")
    }

    private fun <T> mustNotRun(): T = error("this fetch must not run")
}
