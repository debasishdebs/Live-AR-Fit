package com.debasish.livefit.glasses.hud

import com.debasish.livefit.glasses.hud.SwipeKey.Down
import com.debasish.livefit.glasses.hud.SwipeKey.Left
import com.debasish.livefit.glasses.hud.SwipeKey.Right
import com.debasish.livefit.glasses.hud.SwipeKey.Up
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwipeClassifierTest {
    private val fwdShort = Swipe(forward = true, long = false)
    private val fwdLong = Swipe(forward = true, long = true)
    private val backShort = Swipe(forward = false, long = false)
    private val backLong = Swipe(forward = false, long = true)
    private val close = SwipeClassifier.CLOSE_MS

    /** Feeds a recorded key sequence (touch = null key) and returns every swipe emitted, polling the timer at the end. */
    private fun SwipeClassifier.run(vararg events: Pair<Long, SwipeKey?>, endMs: Long? = null): List<Swipe> {
        val out = mutableListOf<Swipe>()
        for ((t, key) in events) (if (key == null) onTouch(t) else onKey(key, t))?.let(out::add)
        endMs?.let { onTimer(it)?.let(out::add) }
        return out
    }

    @Test fun shortForwardSwipeIsOneShortSwipeClosedByItsDownTwin() =
        assertEquals(listOf(fwdShort), SwipeClassifier().run(0L to null, 20L to Right, 60L to Down))

    @Test fun shortBackSwipeIsOneShortSwipe() =
        assertEquals(listOf(backShort), SwipeClassifier().run(0L to null, 20L to Left, 55L to Up))

    @Test fun longSwipeIsTwoHorizontalStepsThenTheTwin() {
        assertEquals(listOf(fwdLong), SwipeClassifier().run(0L to null, 20L to Right, 100L to Right, 140L to Down))
        assertEquals(listOf(backLong), SwipeClassifier().run(0L to null, 20L to Left, 70L to Left, 110L to Up))
    }

    @Test fun moreThanTwoStepsIsStillOneLongSwipe() =
        assertEquals(listOf(fwdLong), SwipeClassifier().run(0L to null, 0L to Right, 60L to Right, 120L to Right, 160L to Down))

    @Test fun nothingIsEmittedBeforeTheGestureCloses() {
        val c = SwipeClassifier()
        assertNull(c.onTouch(0)); assertNull(c.onKey(Right, 20))
        assertNull(c.onTimer(20 + close - 1), "a second step may still come")
        assertEquals(20 + close, c.deadlineMs)
    }

    @Test fun timerClosesTheGestureWhenTheTwinNeverComes() {
        val c = SwipeClassifier()
        c.onTouch(0); c.onKey(Right, 20)
        assertEquals(fwdShort, c.onTimer(20 + close))
        assertNull(c.deadlineMs)
        assertNull(c.onTimer(20 + close + 500), "emitted once")
    }

    @Test fun aLateTwinAfterTheTimerIsSwallowed() {
        val c = SwipeClassifier()
        c.onTouch(0); c.onKey(Right, 20)
        assertEquals(fwdShort, c.onTimer(20 + close))
        assertNull(c.onKey(Down, 20 + close + 30), "the twin of the swipe just emitted")
    }

    @Test fun eachStepExtendsTheWindow() {
        val c = SwipeClassifier()
        c.onKey(Right, 0); c.onKey(Right, close - 10)
        assertNull(c.onTimer(close + 10))
        assertEquals(fwdLong, c.onTimer(2 * close - 10))
    }

    @Test fun aNewTouchClosesAnOpenGesture() {
        val c = SwipeClassifier()
        c.onTouch(0); c.onKey(Left, 20)
        assertEquals(backShort, c.onTouch(100))
        assertNull(c.deadlineMs)
        assertEquals(fwdShort, c.onKey(Right, 120).let { c.onKey(Down, 160) }, "the new gesture is classified on its own")
    }

    @Test fun consecutiveSwipesAreSeparate() = assertEquals(
        listOf(fwdShort, fwdLong, backShort),
        SwipeClassifier().run(
            0L to null, 20L to Right, 60L to Down,
            600L to null, 620L to Right, 690L to Right, 730L to Down,
            1_200L to null, 1_220L to Left, 1_260L to Up,
        ),
    )

    @Test fun oppositeDirectionStartsANewSwipe() {
        val c = SwipeClassifier()
        c.onKey(Right, 0)
        assertEquals(fwdShort, c.onKey(Left, 40))
        assertEquals(backShort, c.onTimer(40 + close))
    }

    @Test fun bareAdbKeysWithoutTouchAreShortSwipes() {
        assertEquals(listOf(fwdShort), SwipeClassifier().run(0L to Right, endMs = close), "lone RIGHT")
        assertEquals(listOf(backShort), SwipeClassifier().run(0L to Left, endMs = close), "lone LEFT")
        assertEquals(listOf(fwdShort), SwipeClassifier().run(0L to Down), "lone DOWN = forward, at once")
        assertEquals(listOf(backShort), SwipeClassifier().run(0L to Up), "lone UP = back, at once")
        assertEquals(listOf(fwdShort, fwdShort), SwipeClassifier().run(0L to Right, 1_000L to Right, endMs = 1_000 + close), "repeated adb presses")
        assertEquals(listOf(fwdShort, fwdShort), SwipeClassifier().run(0L to Down, 500L to Down), "repeated vertical presses")
    }

    @Test fun touchAloneOrATapEmitsNothing() {
        val c = SwipeClassifier()
        assertNull(c.onTouch(0)); assertNull(c.onTimer(10_000)); assertNull(c.deadlineMs)
    }
}
