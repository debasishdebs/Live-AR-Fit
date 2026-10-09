package com.debasish.livefit.map

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A tappable attribution link (spec §5: maptiler.com/copyright, openstreetmap.org/copyright). */
data class AttributionLink(val label: String, val url: String)

/** What every map display must show for the tiles it draws (spec §5, review P2-6). */
data class MapAttribution(val text: String, val mapTilerLogo: Boolean, val links: List<AttributionLink>)

object Attributions {
    val OSM_LINK = AttributionLink("OpenStreetMap", "https://www.openstreetmap.org/copyright")
    val MAPTILER_LINK = AttributionLink("MapTiler", "https://www.maptiler.com/copyright/")
    val OSM = MapAttribution(OSM_ATTRIBUTION, mapTilerLogo = false, links = listOf(OSM_LINK))
    val MAPTILER = MapAttribution("© MapTiler © OpenStreetMap contributors", mapTilerLogo = true, links = listOf(MAPTILER_LINK, OSM_LINK))
}

/** Where tiles come from (spec §2.4, §5): behind an interface so the provider can be swapped. */
interface TileSource {
    val userAgent: String
    val attribution: MapAttribution
    fun url(tile: TileId): String
}

/** Debug/personal fallback only (spec §5): the OSM server is not for public-scale use. */
class OsmTileSource(override val userAgent: String = TileSources.userAgent("dev")) : TileSource {
    override val attribution: MapAttribution = Attributions.OSM
    override fun url(tile: TileId): String = "https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png"
}

/** Production tiles (spec §5): MapTiler raster "streets-v2", 256 px PNG, Free plan. The key never leaves the URL. */
class MapTilerTileSource(private val key: String, override val userAgent: String) : TileSource {
    init { require(key.isNotBlank()) { "MapTiler key is blank" } }
    override val attribution: MapAttribution = Attributions.MAPTILER
    override fun url(tile: TileId): String = "https://api.maptiler.com/maps/streets-v2/256/${tile.z}/${tile.x}/${tile.y}.png?key=$key"
    override fun toString(): String = "MapTilerTileSource(streets-v2/256)"
}

object TileSources {
    const val CONTACT = "d.kanhar@gmail.com"

    fun userAgent(version: String): String = "LiveARFit/$version (com.livear.fit; contact: $CONTACT)"

    /**
     * The build's tile source: MapTiler with a key; without one, debug builds use the OSM server and release builds
     * never get here (the Gradle build fails without LIVEAR_TILES_KEY) — so a keyless release is a programming error.
     */
    fun select(key: String, debug: Boolean, version: String): TileSource = when {
        key.isNotBlank() -> MapTilerTileSource(key, userAgent(version))
        debug -> OsmTileSource(userAgent(version))
        else -> throw IllegalStateException("release build without LIVEAR_TILES_KEY")
    }
}

/** Cache metadata of one tile: absolute expiry and the validators for revalidation (OSM tile policy §3.2). */
data class TileMeta(val expiresAtMs: Long, val etag: String? = null, val lastModified: String? = null)

/**
 * On-disk LRU tile cache; each file = expiry (Long) + ETag (UTF) + Last-Modified (UTF) + PNG. Least recently used files
 * go first once over [maxBytes]. A stale entry is still returned (offline use, revalidation).
 */
class TileDiskCache(private val dir: File, private val maxBytes: Long, private val nowMs: () -> Long = System::currentTimeMillis) {
    class Entry(val bytes: ByteArray, val fresh: Boolean, val meta: TileMeta)

    init { dir.mkdirs() }

    @Synchronized
    fun get(tile: TileId): Entry? {
        val f = file(tile)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { input ->
                val meta = TileMeta(input.readLong(), input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null })
                val bytes = input.readBytes()
                f.setLastModified(nowMs())
                Entry(bytes, nowMs() < meta.expiresAtMs, meta)
            }
        } catch (e: IOException) {
            f.delete(); null
        }
    }

    @Synchronized
    fun put(tile: TileId, bytes: ByteArray, meta: TileMeta) {
        if (!write(tile, bytes, meta)) return
        trim()
    }

    /** A 304: same bytes, new metadata. False when there is no entry to refresh. */
    @Synchronized
    fun refresh(tile: TileId, meta: TileMeta): Boolean {
        val e = get(tile) ?: return false
        return write(tile, e.bytes, meta)
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    private fun write(tile: TileId, bytes: ByteArray, meta: TileMeta): Boolean {
        val f = file(tile)
        val tmp = File(dir, f.name + ".tmp")
        return try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeLong(meta.expiresAtMs)
                out.writeUTF(meta.etag.orEmpty())
                out.writeUTF(meta.lastModified.orEmpty())
                out.write(bytes)
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            f.setLastModified(nowMs())
            true
        } catch (e: IOException) {
            tmp.delete(); false
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".tile") }.orEmpty().toList()

    private fun trim() {
        var total = sizeBytes()
        if (total <= maxBytes) return
        for (f in files().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun file(t: TileId) = File(dir, "${t.z}_${t.x}_${t.y}.tile")

    companion object {
        const val PHONE_MAX_BYTES = 50L * 1024 * 1024
        const val WATCH_MAX_BYTES = 20L * 1024 * 1024
        /** Spec §2.4 "7-day max-age": the lifetime used only when the server gives none (plan ruling, review #11). */
        const val FALLBACK_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * Fresh cache → network → stale cache → null (spec §2.4, OSM tile policy §3.2). The server's lifetime is honoured
 * (max-age, else Expires − Date; 7 days only without either); an expired entry is revalidated with If-None-Match /
 * If-Modified-Since and a 304 keeps the bytes with refreshed metadata. A failed tile is not retried for
 * [retryAfterFailureMs]. Blocking: call it on an IO thread. Only ever asked for the visible tiles (no prefetch).
 */
class HttpTileFetcher(
    private val source: TileSource,
    private val cache: TileDiskCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val timeoutMs: Int = 5_000,
    private val retryAfterFailureMs: Long = 30_000,
) {
    private val failedUntil = HashMap<TileId, Long>()

    private sealed interface Result {
        class Ok(val bytes: ByteArray, val meta: TileMeta) : Result
        class NotModified(val meta: TileMeta) : Result
    }

    fun fetch(tile: TileId): ByteArray? {
        val cached = cache.get(tile)
        if (cached?.fresh == true) return cached.bytes
        val blocked = synchronized(failedUntil) { failedUntil[tile] }
        if (blocked != null && nowMs() < blocked) return cached?.bytes
        val result = try { download(tile, cached?.meta) } catch (e: IOException) { null }
        return when (result) {
            is Result.Ok -> { clearFailure(tile); cache.put(tile, result.bytes, result.meta); result.bytes }
            is Result.NotModified -> if (cached != null) { clearFailure(tile); cache.refresh(tile, result.meta); cached.bytes } else fail(tile, null)
            null -> fail(tile, cached?.bytes)
        }
    }

    private fun fail(tile: TileId, fallback: ByteArray?): ByteArray? {
        synchronized(failedUntil) { failedUntil[tile] = nowMs() + retryAfterFailureMs }
        return fallback
    }

    private fun clearFailure(tile: TileId) = synchronized(failedUntil) { failedUntil.remove(tile) }

    private fun download(tile: TileId, validators: TileMeta?): Result? {
        val c = open(URL(source.url(tile)))
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.setRequestProperty("User-Agent", source.userAgent)
            validators?.etag?.let { c.setRequestProperty("If-None-Match", it) }
            validators?.lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
            val code = c.responseCode
            val now = nowMs()
            val lifetime = lifetimeMs(c.getHeaderField("Cache-Control"), c.getHeaderField("Expires"), c.getHeaderField("Date"), now)
            val meta = TileMeta(now + lifetime, c.getHeaderField("ETag") ?: validators?.etag, c.getHeaderField("Last-Modified") ?: validators?.lastModified)
            return when (code) {
                200 -> Result.Ok(c.inputStream.use { it.readBytes() }, meta)
                304 -> Result.NotModified(meta)
                else -> null
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private val MAX_AGE = Regex("(?:^|[,\\s])max-age=(\\d+)")

        /** Server lifetime: Cache-Control max-age, else Expires − (Date or now); an unparsable Expires = expired; neither → 7 days. */
        fun lifetimeMs(cacheControl: String?, expires: String?, date: String?, nowMs: Long): Long {
            MAX_AGE.find(cacheControl.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { return it * 1_000 }
            if (expires != null) {
                val exp = httpDate(expires) ?: return 0
                return (exp - (date?.let(::httpDate) ?: nowMs)).coerceAtLeast(0)
            }
            return TileDiskCache.FALLBACK_LIFETIME_MS
        }

        private fun httpDate(s: String): Long? =
            runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }
}
