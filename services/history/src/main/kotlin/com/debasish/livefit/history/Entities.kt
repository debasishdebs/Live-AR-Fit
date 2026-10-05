package com.debasish.livefit.history

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "session", primaryKeys = ["id"])
data class SessionEntity(
    val id: String,
    /** JSON of SessionSummary once finalized; null while open. */
    val summaryJson: String?,
    /** SessionStatus name while open/finalized, or "Discarded" (tombstone of an abandoned start). */
    val status: String,
    val startMs: Long,
    val createdAtMs: Long,
    /** Set once the session's end is known (hub restart keeps Stopping and the 24 h deadline). */
    val endedAtMs: Long? = null,
    val endReason: String? = null,
)

@Entity(tableName = "delta", primaryKeys = ["sessionId", "seq"])
data class DeltaEntity(val sessionId: String, val seq: Long, val json: String)

@Entity(tableName = "sample", primaryKeys = ["sessionId", "tMs"], indices = [Index("sessionId")])
data class SampleEntity(
    val sessionId: String,
    val tMs: Long,
    val hr: Int?,
    val steps: Int,
    val distanceKm: Double,
    val kcal: Double,
    val speedKmh: Double?,
    val provenance: String,
)
