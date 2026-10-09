package com.debasish.livefit.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDelta(d: DeltaEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSamples(s: List<SampleEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSession(s: SessionEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRoutePoints(p: List<RoutePointEntity>)
    /** Review r3: an existing row keeps the EARLIEST receipt — a later-stamped replay never becomes authoritative. */
    @Query("UPDATE route_point SET receivedAtMs = :receivedAtMs WHERE sessionId = :id AND source = :source AND fixTimeMs = :fixTimeMs AND receivedAtMs > :receivedAtMs")
    suspend fun keepEarliestReceipt(id: String, source: String, fixTimeMs: Long, receivedAtMs: Long)
    /** Phone-time order; "Watch" sorts after "Phone", so DESC puts the watch first on equal times (spec §2.2); NULL phone times last. */
    @Query("SELECT * FROM route_point WHERE sessionId = :id ORDER BY phoneTimeMs IS NULL, phoneTimeMs, source DESC, fixTimeMs") suspend fun routePoints(id: String): List<RoutePointEntity>
    /** Review #2: only rows that are null or mapped with another offset are written. */
    @Query("UPDATE route_point SET phoneTimeMs = fixTimeMs - :offsetMs WHERE sessionId = :id AND source = 'Watch' AND (phoneTimeMs IS NULL OR phoneTimeMs != fixTimeMs - :offsetMs)")
    suspend fun normalizeWatchTimes(id: String, offsetMs: Long)
    @Query("DELETE FROM route_point WHERE sessionId = :id") suspend fun deleteRoute(id: String)
    @Query("DELETE FROM route_point WHERE sessionId IN (SELECT id FROM session WHERE summaryJson IS NOT NULL)") suspend fun clearFinishedRoutes()

    /** Route rows are never written for a Discarded or Cleared session: its tombstone wins, even after a restart. */
    @Transaction
    suspend fun storeRoutePoints(id: String, rows: List<RoutePointEntity>) {
        if (rows.isEmpty()) return
        val status = session(id)?.status
        if (status == DISCARDED || status == CLEARED) return
        insertRoutePoints(rows) // identity and phone time: first insert wins (normalizeWatchTimes rewrites phone time)
        for (r in rows) keepEarliestReceipt(id, r.source, r.fixTimeMs, r.receivedAtMs) // receipt: min of all inserts
    }

    @Query("SELECT seq FROM delta WHERE sessionId = :id ORDER BY seq") suspend fun seqs(id: String): List<Long>
    @Query("SELECT json FROM delta WHERE sessionId = :id ORDER BY seq") suspend fun deltaJson(id: String): List<String>
    @Query("SELECT id FROM session WHERE summaryJson IS NULL AND status != 'Discarded' AND EXISTS (SELECT 1 FROM delta WHERE delta.sessionId = session.id) ORDER BY createdAtMs") suspend fun openIds(): List<String>
    @Query("SELECT * FROM session WHERE id = :id") suspend fun session(id: String): SessionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(s: SessionEntity)
    @Query("UPDATE session SET endedAtMs = COALESCE(endedAtMs, :at), endReason = COALESCE(endReason, :reason) WHERE id = :id") suspend fun markEnded(id: String, reason: String?, at: Long)
    @Query("UPDATE session SET summaryJson = :json, status = :status, startMs = :startMs WHERE id = :id AND status NOT IN ('Discarded', 'Cleared')") suspend fun finalize(id: String, json: String, status: String, startMs: Long)
    @Query("SELECT summaryJson FROM session WHERE summaryJson IS NOT NULL AND status != 'Cleared' ORDER BY startMs DESC") fun summaries(): Flow<List<String>>
    @Query("SELECT * FROM sample WHERE sessionId = :id ORDER BY tMs") suspend fun samples(id: String): List<SampleEntity>
    @Query("DELETE FROM delta WHERE sessionId = :id") suspend fun deleteDeltas(id: String)
    @Query("DELETE FROM sample WHERE sessionId = :id") suspend fun deleteSamples(id: String)
    @Query("DELETE FROM session WHERE id = :id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM delta WHERE sessionId IN (SELECT id FROM session WHERE summaryJson IS NOT NULL)") suspend fun clearFinishedDeltas()
    @Query("DELETE FROM sample WHERE sessionId IN (SELECT id FROM session WHERE summaryJson IS NOT NULL)") suspend fun clearFinishedSamples()
    @Query("UPDATE session SET summaryJson = '', status = 'Cleared' WHERE summaryJson IS NOT NULL") suspend fun hideFinishedSessions()

    @Transaction
    suspend fun storeDelta(session: SessionEntity, delta: DeltaEntity, samples: List<SampleEntity>, routes: List<RoutePointEntity>): List<Long> {
        insertSession(session)
        insertDelta(delta)
        insertSamples(samples)
        storeRoutePoints(delta.sessionId, routes)
        return seqs(delta.sessionId)
    }

    /** Deletes the data but keeps a tombstone row so after a restart the session stays Discarded: late deltas are still stored but hidden by lifecycle()/openSessionIds(). */
    @Transaction
    suspend fun discard(id: String, now: Long) {
        deleteDeltas(id); deleteSamples(id); deleteRoute(id)
        upsertSession(SessionEntity(id, summaryJson = null, status = DISCARDED, startMs = 0, createdAtMs = now))
    }

    @Transaction
    suspend fun markEndedOrCreate(id: String, reason: String?, at: Long, now: Long) {
        insertSession(SessionEntity(id, summaryJson = null, status = "Active", startMs = 0, createdAtMs = now))
        markEnded(id, reason, at)
    }

    /** Finalizes even a session with no stored rows (as the in-memory store does); a Discarded tombstone is left alone. */
    @Transaction
    suspend fun finalizeOrCreate(id: String, json: String, status: String, startMs: Long, now: Long) {
        insertSession(SessionEntity(id, summaryJson = null, status = "Active", startMs = startMs, createdAtMs = now))
        finalize(id, json, status, startMs)
    }

    companion object {
        const val DISCARDED = "Discarded"
        /** Tombstone of a finalized session removed by "Clear history": hidden, data deleted, still Finalized for the hub. */
        const val CLEARED = "Cleared"
    }

    /**
     * "Clear history": deletes finalized sessions' data. Open sessions (running, syncing, stopping) keep everything,
     * and every session row stays as a tombstone so stale watch traffic for it is still rejected after a restart.
     */
    @Transaction
    suspend fun clearFinished() { clearFinishedDeltas(); clearFinishedSamples(); clearFinishedRoutes(); hideFinishedSessions() }
}
