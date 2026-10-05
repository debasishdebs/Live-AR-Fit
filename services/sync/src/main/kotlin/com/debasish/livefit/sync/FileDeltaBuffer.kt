package com.debasish.livefit.sync

import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutType
import kotlinx.serialization.Serializable
import java.io.File

/** Folded acked deltas: [delta] = all events + the last [FileDeltaBuffer.CHECKPOINT_SAMPLES] samples; hr* cover every folded sample. */
@Serializable
data class Checkpoint(val delta: SessionDelta, val hrSum: Long = 0, val hrCount: Int = 0, val hrMax: Int? = null)

@Serializable
data class WatchSessionHeader(val sessionId: String, val type: WorkoutType, val startMs: Long, val lastSeq: Long, val finalSeq: Long? = null)

/**
 * One directory per session; files are written atomically (temp + rename).
 * Acked deltas are folded into `checkpoint.json` (all events + the last [CHECKPOINT_SAMPLES] samples as one
 * SessionDelta whose seq = highest folded seq, plus HR sum/count/max of every folded sample) before their files are deleted.
 * Delta files are filtered by the seq in their filename before decoding, so replay stays linear per ack.
 */
class FileDeltaBuffer(val dir: File) {
    init { dir.mkdirs() }

    private fun atomicWrite(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }

    private inline fun <reified T> read(name: String): T? =
        File(dir, name).takeIf { it.exists() }?.let { runCatching { Wire.decode<T>(it.readText()) }.getOrNull() }

    fun readHeader(): WatchSessionHeader? = read(HEADER)
    fun writeHeader(h: WatchSessionHeader) = atomicWrite(HEADER, Wire.encode(h))
    fun readCheckpoint(): Checkpoint? = read(CHECKPOINT)

    fun put(d: SessionDelta) = atomicWrite("d-${d.seq}.json", Wire.encode(d))

    private fun seqOf(f: File): Long? = f.name.removePrefix("d-").removeSuffix(".json").toLongOrNull()

    /** Delta files (seq, file) with seq in [range], by filename only; ascending. */
    private fun deltaFiles(range: LongRange? = null): List<Pair<Long, File>> =
        dir.listFiles { f -> f.name.startsWith("d-") && f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { f -> seqOf(f)?.let { it to f } }
            .filter { range == null || it.first in range }
            .sortedBy { it.first }

    private fun decode(files: List<Pair<Long, File>>): List<SessionDelta> =
        files.mapNotNull { (_, f) -> runCatching { Wire.decode<SessionDelta>(f.readText()) }.getOrNull() }.sortedBy { it.seq }

    /** Unacked deltas in seq order; files the checkpoint already covers are never decoded. */
    fun unacked(): List<SessionDelta> {
        val folded = readCheckpoint()?.delta?.seq ?: -1
        return decode(deltaFiles((folded + 1)..Long.MAX_VALUE))
    }

    /** Checkpoint first, then delete: a crash in between leaves files the checkpoint covers, which [unacked] ignores. */
    fun ackUpTo(seq: Long) {
        val old = readCheckpoint()
        val folded = old?.delta?.seq ?: -1
        val pruned = decode(deltaFiles((folded + 1)..seq))
        if (pruned.isNotEmpty()) {
            val hrs = pruned.flatMap { it.samples }.mapNotNull { it.hr }
            val cp = Checkpoint(
                delta = SessionDelta(
                    sessionId = pruned.last().sessionId,
                    seq = pruned.last().seq,
                    events = old?.delta?.events.orEmpty() + pruned.flatMap { it.events },
                    samples = (old?.delta?.samples.orEmpty() + pruned.flatMap { it.samples }).takeLast(CHECKPOINT_SAMPLES),
                    provenance = pruned.last().provenance,
                    final = false,
                ),
                hrSum = (old?.hrSum ?: 0) + hrs.sumOf { it.toLong() },
                hrCount = (old?.hrCount ?: 0) + hrs.size,
                hrMax = listOfNotNull(old?.hrMax, hrs.maxOrNull()).maxOrNull(),
            )
            atomicWrite(CHECKPOINT, Wire.encode(cp))
        }
        val coveredTo = readCheckpoint()?.delta?.seq ?: return
        deltaFiles(0..coveredTo).forEach { it.second.delete() }
    }

    fun delete() { dir.deleteRecursively() }

    companion object {
        private const val HEADER = "header.json"
        private const val CHECKPOINT = "checkpoint.json"
        /** Enough for the watch's 60 s trend line; average/max HR come from the checkpoint's hr* fields instead. */
        const val CHECKPOINT_SAMPLES = 120
    }
}
