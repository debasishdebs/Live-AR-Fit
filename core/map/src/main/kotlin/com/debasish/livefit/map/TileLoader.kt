package com.debasish.livefit.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Loads the visible tiles in the background (review #5/#6): [show] returns at once; each missing visible tile loads on
 * its own, at most [maxConcurrent] at a time; a failed or throwing load frees its slot in `finally` and is retried every
 * [retryEveryMs] while the tile stays visible ([load] is expected to honour HttpTileFetcher's backoff). Renderers draw
 * whatever [tiles] holds right now. At most [maxCached] decoded tiles are kept; visible ones are never evicted.
 * Visibility is owned by the caller's thread (review r2 #1): a job re-checks it after waiting for a slot, [hide] bumps
 * [generation], and a [show] with an older generation (an in-flight async render) is ignored.
 */
class TileLoader<T : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (TileId) -> T?,
    private val maxConcurrent: Int = 4,
    private val retryEveryMs: Long = 5_000,
    private val maxCached: Int = 30,
    private val log: (String) -> Unit = {},
) {
    private val _tiles = MutableStateFlow<Map<TileId, T>>(emptyMap())
    val tiles: StateFlow<Map<TileId, T>> = _tiles
    private val lock = Any()
    private var visible: Set<TileId> = emptySet()
    private var gen = 0L
    private val inFlight = HashSet<TileId>()
    private val slots = Semaphore(maxConcurrent)

    /** Bumped by [hide]; capture it before rendering asynchronously and pass it to [show]. */
    val generation: Long get() = synchronized(lock) { gen }
    val visibleTiles: Set<TileId> get() = synchronized(lock) { visible }

    /** The tiles on screen now. Ignored when [generation] is given and no longer current. Never suspends or blocks. */
    fun show(visible: Collection<TileId>, generation: Long? = null) {
        synchronized(lock) {
            if (generation != null && generation != gen) return
            this.visible = visible.toSet()
        }
        launchMissing()
    }

    /** The map is no longer shown: nothing visible, queued loads are dropped, older-generation shows are ignored. */
    fun hide() = synchronized(lock) { gen++; visible = emptySet() }

    /** Retries still-missing visible tiles every [retryEveryMs]. */
    fun start(): Job = scope.launch { while (isActive) { delay(retryEveryMs); launchMissing() } }

    private fun launchMissing() {
        val todo = synchronized(lock) { visible.filter { it !in _tiles.value && inFlight.add(it) } }
        for (t in todo) scope.launch {
            try {
                // Re-check after waiting for a slot: a tile that left the view meanwhile is not loaded (review r2 #1).
                val value = slots.withPermit { if (synchronized(lock) { t !in visible }) null else load(t) }
                if (value != null) store(t, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("tile $t failed: $e")
            } finally {
                synchronized(lock) { inFlight.remove(t) }
            }
        }
    }

    private fun store(t: TileId, value: T) = synchronized(lock) {
        val next = LinkedHashMap(_tiles.value)
        next.remove(t)
        next[t] = value
        val keys = next.keys.iterator()
        while (next.size > maxCached && keys.hasNext()) if (keys.next() !in visible) keys.remove()
        _tiles.value = next
    }
}
