package com.debasish.livefit.map

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TilesTest {
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val userAgents = CopyOnWriteArrayList<String>()
    private val ifNoneMatch = CopyOnWriteArrayList<String?>()
    private val ifModifiedSince = CopyOnWriteArrayList<String?>()
    @Volatile private var status = 200
    @Volatile private var cacheControl: String? = "max-age=60"
    @Volatile private var expires: String? = null
    @Volatile private var etag: String? = null
    @Volatile private var lastModified: String? = null
    private val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
    private var now = 1_000_000L
    private val tile = TileId(18, 1, 2)
    private val day = 24L * 3600 * 1000

    @BeforeTest fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            userAgents += ex.requestHeaders.getFirst("User-Agent").orEmpty()
            ifNoneMatch += ex.requestHeaders.getFirst("If-None-Match")
            ifModifiedSince += ex.requestHeaders.getFirst("If-Modified-Since")
            cacheControl?.let { ex.responseHeaders.add("Cache-Control", it) }
            expires?.let { ex.responseHeaders.add("Expires", it) }
            etag?.let { ex.responseHeaders.add("ETag", it) }
            lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
            when (status) {
                200 -> { ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) } }
                else -> { ex.sendResponseHeaders(status, -1); ex.close() }
            }
        }
        server.start()
    }

    @AfterTest fun down() = server.stop(0)

    private val source = object : TileSource {
        override val userAgent = "LiveARFit/test"
        override val attribution = Attributions.OSM
        override val cacheId = "test"
        override fun url(tile: TileId) = "http://127.0.0.1:${server.address.port}/${tile.z}/${tile.x}/${tile.y}.png"
    }

    @Test fun sourcesHaveDistinctCacheIds() {
        assertEquals("osm", OsmTileSource().cacheId)
        assertEquals("maptiler-streets-v2-256", MapTilerTileSource("k", "ua").cacheId)
    }

    /** Review fix: a MapTiler fetcher never serves (or revalidates with the ETag of) a tile cached from the OSM server. */
    @Test fun mapTilerFetcherNeverServesAnOsmCachedTile() {
        val root = Files.createTempDirectory("tiles-root").toFile()
        val osmCache = TileDiskCache.forSource(root, OsmTileSource(), 1_000_000, { now })
        osmCache.put(tile, png, TileMeta(now + day, etag = "\"osm\""))
        val mt = MapTilerTileSource("k", "ua")
        val local = "http://127.0.0.1:${server.address.port}/x.png"
        val f = HttpTileFetcher(mt, TileDiskCache.forSource(root, mt, 1_000_000, { now }), { now }, open = { java.net.URL(local).openConnection() as java.net.HttpURLConnection })
        status = 503
        assertNull(f.fetch(tile), "no OSM bytes under MapTiler attribution")
        assertEquals(1, requests.get(), "went to the network, not the OSM cache")
        assertEquals(listOf<String?>(null), ifNoneMatch.toList(), "no OSM ETag sent to MapTiler")
        assertNotNull(osmCache.get(tile), "the OSM cache is untouched")
    }

    /** Review fix: legacy flat tiles go on the first cache access (the fetcher's IO thread), not in the constructor (Main). */
    @Test fun legacyFlatTilesAreDeleted() {
        val root = Files.createTempDirectory("tiles-legacy").toFile()
        val legacy = File(root, "18_1_2.tile").apply { writeBytes(png) }
        val otherSource = File(root, "osm").apply { mkdirs() }
        val kept = File(otherSource, "18_1_2.tile").apply { writeBytes(png) }
        val c = TileDiskCache.forSource(root, MapTilerTileSource("k", "ua"), 1_000_000, { now })
        assertTrue(legacy.exists(), "construction does no file deletes")
        assertNull(c.get(tile))
        assertFalse(legacy.exists())
        assertTrue(kept.exists(), "subdirectories are never touched")
        assertTrue(File(root, "maptiler-streets-v2-256").isDirectory)
    }

    private fun cache(dir: File = Files.createTempDirectory("tiles").toFile()) = TileDiskCache(dir, 1_000_000, { now })
    private fun fetcher(c: TileDiskCache = cache()) = HttpTileFetcher(source, c, { now })
    private fun http(ms: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    @Test fun osmSourceUrlAndAttribution() {
        val osm = OsmTileSource()
        assertEquals("https://tile.openstreetmap.org/18/1/2.png", osm.url(tile))
        assertEquals("© OpenStreetMap contributors", osm.attribution.text)
        assertFalse(osm.attribution.mapTilerLogo)
        assertTrue(osm.userAgent.startsWith("LiveARFit/"), "app-specific User-Agent")
    }

    @Test fun mapTilerStreetsV2RasterWithLogoAndBothLinks() {
        val mt = MapTilerTileSource("k3y", TileSources.userAgent("1.0.0"))
        assertEquals("https://api.maptiler.com/maps/streets-v2/256/18/1/2.png?key=k3y", mt.url(tile))
        assertEquals("© MapTiler © OpenStreetMap contributors", mt.attribution.text)
        assertTrue(mt.attribution.mapTilerLogo)
        assertEquals(listOf("https://www.maptiler.com/copyright/", "https://www.openstreetmap.org/copyright"), mt.attribution.links.map { it.url })
    }

    @Test fun userAgentNamesAppVersionAndContact() =
        assertEquals("LiveARFit/1.0.0 (com.livear.fit; contact: d.kanhar@gmail.com)", TileSources.userAgent("1.0.0"))

    /** Review focus 3: the key is in the URL only — never in the User-Agent, toString or anything logged. */
    @Test fun theKeyStaysOutOfTheUserAgentAndToString() {
        val mt = TileSources.select("s3cretKey", debug = false, version = "1.0.0")
        assertFalse("s3cretKey" in mt.userAgent)
        assertFalse("s3cretKey" in mt.toString())
    }

    @Test fun selectFallsBackToOsmOnlyInDebug() {
        assertTrue(TileSources.select("", debug = true, version = "1.0.0") is OsmTileSource)
        assertTrue(TileSources.select("k", debug = true, version = "1.0.0") is MapTilerTileSource)
        assertFailsWith<IllegalStateException> { TileSources.select(" ", debug = false, version = "1.0.0") }
    }

    /** Review focus 3: a revoked key (401/403) or an exhausted Free plan (429) → route only, no retry storm. */
    @Test fun keyAndQuotaErrorsBackOffLikeAnyFailure() {
        for (code in listOf(401, 403, 429)) {
            requests.set(0); status = code
            val f = fetcher()
            assertNull(f.fetch(tile), "HTTP $code")
            assertNull(f.fetch(tile))
            assertEquals(1, requests.get(), "HTTP $code: backed off")
        }
    }

    @Test fun sendsTheAppUserAgentAndServesRepeatsFromCache() {
        val f = fetcher()
        assertContentEquals(png, f.fetch(tile))
        assertContentEquals(png, f.fetch(tile))
        assertEquals(1, requests.get())
        assertEquals(listOf("LiveARFit/test"), userAgents.toList())
        assertNull(ifNoneMatch.single(), "a first download carries no validators")
    }

    @Test fun honoursMaxAge() {
        val f = fetcher()
        f.fetch(tile)
        now += 59_000; f.fetch(tile)
        assertEquals(1, requests.get())
        now += 2_000; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    /** Review #11 / OSM tile policy §3.2: a server lifetime longer than 7 days is honoured, not capped. */
    @Test fun longMaxAgeIsPreserved() {
        assertEquals(99_999_999_000L, HttpTileFetcher.lifetimeMs("public, max-age=99999999", null, null, now))
        cacheControl = "max-age=1209600" // 14 days
        val f = fetcher()
        f.fetch(tile)
        now += 8 * day; f.fetch(tile)
        assertEquals(1, requests.get(), "still fresh after 8 days")
        now += 7 * day; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    @Test fun expiresIsHonouredWhenThereIsNoMaxAge() {
        assertEquals(7_200_000L, HttpTileFetcher.lifetimeMs(null, http(now + 7_200_000), http(now), now))
        assertEquals(0L, HttpTileFetcher.lifetimeMs(null, "0", null, now), "an invalid Expires means already expired")
        assertEquals(30_000L, HttpTileFetcher.lifetimeMs("max-age=30", http(now + 7_200_000), null, now), "max-age wins over Expires")
        // The test server stamps a real Date header, so this part runs on the real clock (100 s margins either side).
        now = System.currentTimeMillis()
        cacheControl = null; expires = http(now + 7_200_000)
        val f = fetcher()
        f.fetch(tile)
        now += 7_100_000; f.fetch(tile)
        assertEquals(1, requests.get())
        now += 200_000; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    /** Spec §2.4: 7 days only when the server sends neither max-age nor Expires. */
    @Test fun headerlessResponseFallsBackToSevenDays() {
        assertEquals(7 * day, HttpTileFetcher.lifetimeMs(null, null, null, now))
        assertEquals(7 * day, HttpTileFetcher.lifetimeMs("public", null, null, now))
        cacheControl = null
        val f = fetcher()
        f.fetch(tile)
        now += 7 * day - 1; f.fetch(tile)
        assertEquals(1, requests.get())
        now += 2; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    /** Review #11: an expired entry is revalidated with its ETag and Last-Modified. */
    @Test fun expiredEntrySendsValidators() {
        etag = "\"abc\""; lastModified = http(now - day)
        val f = fetcher()
        f.fetch(tile)
        now += 61_000; f.fetch(tile)
        assertEquals(listOf(null, "\"abc\""), ifNoneMatch.toList())
        assertEquals(listOf(null, http(now - 61_000 - day)), ifModifiedSince.toList())
    }

    /** Review #11: 304 keeps the cached bytes and refreshes the lifetime (and validators) from the new headers. */
    @Test fun notModifiedKeepsTheBytesAndRefreshesMetadata() {
        etag = "\"abc\""
        val c = cache()
        val f = fetcher(c)
        f.fetch(tile)
        now += 61_000; status = 304; cacheControl = "max-age=120"; etag = "\"abd\""
        assertContentEquals(png, f.fetch(tile))
        assertEquals(2, requests.get())
        val e = assertNotNull(c.get(tile))
        assertTrue(e.fresh)
        assertEquals("\"abd\"", e.meta.etag)
        assertContentEquals(png, e.bytes)
        now += 100_000; f.fetch(tile)
        assertEquals(2, requests.get(), "fresh again for the new 120 s")
    }

    @Test fun offlineServesTheStaleCopy() {
        val f = fetcher()
        f.fetch(tile)
        now += 61_000; status = 500
        assertContentEquals(png, f.fetch(tile))
        assertEquals(2, requests.get())
    }

    @Test fun failuresBackOffThirtySeconds() {
        status = 503
        val f = fetcher()
        assertNull(f.fetch(tile))
        assertNull(f.fetch(tile))
        assertEquals(1, requests.get(), "no retry storm while offline")
        now += 30_001
        assertNull(f.fetch(tile))
        assertEquals(2, requests.get())
    }

    @Test fun lruEvictsTheLeastRecentlyUsed() {
        val dir = Files.createTempDirectory("lru").toFile()
        val overhead = 8 + 2 + 2 // expiry + two empty validator strings
        val cache = TileDiskCache(dir, maxBytes = 2 * (overhead + 100) + 50L, nowMs = { now })
        val a = TileId(18, 0, 0); val b = TileId(18, 0, 1); val c = TileId(18, 0, 2)
        cache.put(a, ByteArray(100), TileMeta(now + 60_000)); now += 10_000
        cache.put(b, ByteArray(100), TileMeta(now + 60_000)); now += 10_000
        assertNotNull(cache.get(a)); now += 10_000 // a is now more recent than b
        cache.put(c, ByteArray(100), TileMeta(now + 60_000))
        assertNull(cache.get(b), "least recently used evicted")
        assertNotNull(cache.get(a)); assertNotNull(cache.get(c))
        assertTrue(cache.sizeBytes() <= 2 * (overhead + 100) + 50L)
    }

    @Test fun expiredEntryIsMarkedStale() {
        val cache = TileDiskCache(Files.createTempDirectory("exp").toFile(), 1_000_000, { now })
        cache.put(tile, png, TileMeta(now + 1_000, etag = "\"e\""))
        assertTrue(cache.get(tile)!!.fresh)
        now += 1_001
        val stale = cache.get(tile)!!
        assertFalse(stale.fresh)
        assertEquals("\"e\"", stale.meta.etag)
        assertFalse(cache.refresh(TileId(18, 9, 9), TileMeta(now + 1_000)), "nothing to refresh")
    }
}
