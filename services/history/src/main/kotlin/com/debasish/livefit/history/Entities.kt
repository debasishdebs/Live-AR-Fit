package com.debasish.livefit.history

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "session", primaryKeys = ["id"])
data class SessionEntity(
    val id: String,
    /** JSON of SessionSummary once finalized ("" once cleared); null while open. */
    val summaryJson: String?,
    /** SessionStatus name while open/finalized, "Discarded" (tombstone of an abandoned start) or "Cleared" (finalized, removed by Clear history). */
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

/**
 * One route row (spec §2.2). [fixTimeMs] is the measuring device's own clock — the replay-safe identity, never changed;
 * [phoneTimeMs] is the calibrated phone time used for ordering, null until the watch clock is calibrated.
 */
@Entity(tableName = "route_point", primaryKeys = ["sessionId", "source", "fixTimeMs"], indices = [Index("sessionId")])
data class RoutePointEntity(
    val sessionId: String,
    val source: String,
    val fixTimeMs: Long,
    val phoneTimeMs: Long?,
    /** Phone time the fix was first received; never rewritten (review r2 #2). */
    val receivedAtMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val bearingDeg: Float?,
)
