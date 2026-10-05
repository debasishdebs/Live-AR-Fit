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
    @Query("SELECT seq FROM delta WHERE sessionId = :id ORDER BY seq") suspend fun seqs(id: String): List<Long>
    @Query("SELECT json FROM delta WHERE sessionId = :id ORDER BY seq") suspend fun deltaJson(id: String): List<String>
    @Query("SELECT id FROM session WHERE summaryJson IS NULL AND status != 'Discarded' AND EXISTS (SELECT 1 FROM delta WHERE delta.sessionId = session.id) ORDER BY createdAtMs") suspend fun openIds(): List<String>
    @Query("SELECT * FROM session WHERE id = :id") suspend fun session(id: String): SessionEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(s: SessionEntity)
    @Query("UPDATE session SET endedAtMs = COALESCE(endedAtMs, :at), endReason = COALESCE(endReason, :reason) WHERE id = :id") suspend fun markEnded(id: String, reason: String?, at: Long)
    @Query("UPDATE session SET summaryJson = :json, status = :status, startMs = :startMs WHERE id = :id AND status != 'Discarded'") suspend fun finalize(id: String, json: String, status: String, startMs: Long)
    @Query("SELECT summaryJson FROM session WHERE summaryJson IS NOT NULL ORDER BY startMs DESC") fun summaries(): Flow<List<String>>
    @Query("SELECT * FROM sample WHERE sessionId = :id ORDER BY tMs") suspend fun samples(id: String): List<SampleEntity>
    @Query("DELETE FROM delta WHERE sessionId = :id") suspend fun deleteDeltas(id: String)
    @Query("DELETE FROM sample WHERE sessionId = :id") suspend fun deleteSamples(id: String)
    @Query("DELETE FROM session WHERE id = :id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM delta") suspend fun clearDeltas()
    @Query("DELETE FROM sample") suspend fun clearSamples()
    @Query("DELETE FROM session") suspend fun clearSessions()

    @Transaction
    suspend fun storeDelta(session: SessionEntity, delta: DeltaEntity, samples: List<SampleEntity>): List<Long> {
        insertSession(session)
        insertDelta(delta)
        insertSamples(samples)
        return seqs(delta.sessionId)
    }

    /** Deletes the data but keeps a tombstone row so after a restart the session stays Discarded: late deltas are still stored but hidden by lifecycle()/openSessionIds(). */
    @Transaction
    suspend fun discard(id: String, now: Long) {
        deleteDeltas(id); deleteSamples(id)
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

    companion object { const val DISCARDED = "Discarded" }

    @Transaction
    suspend fun clearAll() { clearDeltas(); clearSamples(); clearSessions() }
}
