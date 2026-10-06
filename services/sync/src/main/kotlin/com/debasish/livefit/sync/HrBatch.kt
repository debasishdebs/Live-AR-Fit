package com.debasish.livefit.sync

import com.debasish.livefit.model.Sample

/**
 * One exercise update → samples. Screen-off batching delivers several heart-rate points per update: each is kept
 * at its measurement time ([hr] = time ms to bpm; never later than [nowMs]) so peaks and averages stay right.
 * Every sample carries the latest totals; an update without heart rate is one totals sample at [nowMs].
 */
fun batchSamples(hr: List<Pair<Long, Int>>, nowMs: Long, steps: Int, km: Double, kcal: Double, speedKmh: Double?): List<Sample> =
    if (hr.isEmpty()) listOf(Sample(nowMs, null, steps, km, kcal, speedKmh))
    else hr.sortedBy { it.first }.map { (t, bpm) -> Sample(minOf(t, nowMs), bpm, steps, km, kcal, speedKmh) }
