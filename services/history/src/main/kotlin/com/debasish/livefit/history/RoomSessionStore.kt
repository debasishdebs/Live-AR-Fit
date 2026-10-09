package com.debasish.livefit.history

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.RouteFix
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.toRouteFix
import com.debasish.livefit.services.HistoryStore
import com.debasish.livefit.services.RouteStore
import com.debasish.livefit.services.SessionLifecycle
import com.debasish.livefit.services.StoredSessionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSessionStore(db: HistoryDatabase, private val now: () -> Long = System::currentTimeMillis) : HistoryStore, RouteStore {
    private val dao = db.dao()

    override suspend fun storeDelta(delta: SessionDelta): Long {
        val prov = Wire.encode<Provenance>(delta.provenance)
        val seqs = dao.storeDelta(
            session = SessionEntity(delta.sessionId, summaryJson = null, status = SessionStatus.Active.name,
                startMs = delta.samples.firstOrNull()?.tMs ?: delta.events.firstOrNull()?.tMs ?: 0, createdAtMs = now()),
            delta = DeltaEntity(delta.sessionId, delta.seq, Wire.encode(delta)),
            samples = delta.samples.map { SampleEntity(delta.sessionId, it.tMs, it.hr, it.stepsTotal, it.distanceKmTotal, it.kcalTotal, it.speedKmh, prov) },
            // Review #1: the delta's accurate fixes become route rows in the same transaction (uncalibrated: phoneTimeMs = null).
            routes = delta.locations.mapNotNull { it.toRouteFix(FixSource.Watch, phoneTimeMs = null, receivedAtMs = now()) }.map { entity(delta.sessionId, it) },
        )
        var s = -1L
        for (seq in seqs) { if (seq == s + 1) s = seq else if (seq > s + 1) break }
        return s
    }

    override suspend fun deltas(sessionId: String): List<SessionDelta> = dao.deltaJson(sessionId).map { Wire.decode(it) }
    override suspend fun openSessionIds(): List<String> = dao.openIds()
    override suspend fun finalize(summary: SessionSummary) =
        dao.finalizeOrCreate(summary.id, Wire.encode(summary), summary.status.name, summary.startMs, now())
    override suspend fun discard(sessionId: String) = dao.discard(sessionId, now())

    override suspend fun lifecycle(sessionId: String): SessionLifecycle? {
        val e = dao.session(sessionId) ?: return null
        if (e.status == HistoryDao.DISCARDED) return SessionLifecycle(StoredSessionState.Discarded)
        val state = if (e.summaryJson != null || e.status == HistoryDao.CLEARED) StoredSessionState.Finalized else StoredSessionState.Open
        return SessionLifecycle(state, e.endReason?.let { EndReason.valueOf(it) }, e.endedAtMs)
    }

    override suspend fun markEnded(sessionId: String, endReason: EndReason?, endedAtMs: Long) =
        dao.markEndedOrCreate(sessionId, endReason?.name, endedAtMs, now())

    override val sessions: Flow<List<SessionSummary>> = dao.summaries().map { list -> list.map { Wire.decode<SessionSummary>(it) } }
    override suspend fun samples(sessionId: String): List<Sample> =
        dao.samples(sessionId).map { Sample(it.tMs, it.hr, it.steps, it.distanceKm, it.kcal, it.speedKmh) }
    override suspend fun clearFinished() = dao.clearFinished()

    override suspend fun storeRouteFixes(sessionId: String, fixes: List<RouteFix>) = dao.storeRoutePoints(sessionId, fixes.map { entity(sessionId, it) })

    override suspend fun normalizeWatchTimes(sessionId: String, watchOffsetMs: Long) = dao.normalizeWatchTimes(sessionId, watchOffsetMs)

    override suspend fun routeFixes(sessionId: String): List<RouteFix> = dao.routePoints(sessionId).map {
        RouteFix(FixSource.valueOf(it.source), it.lat, it.lon, it.accuracyM, deviceTimeMs = it.fixTimeMs, phoneTimeMs = it.phoneTimeMs, receivedAtMs = it.receivedAtMs, bearingDeg = it.bearingDeg)
    }

    private fun entity(sessionId: String, f: RouteFix) =
        RoutePointEntity(sessionId, f.source.name, f.deviceTimeMs, f.phoneTimeMs, f.receivedAtMs, f.lat, f.lon, f.accuracyM, f.bearingDeg)
}
