package com.debasish.livefit.confirm

import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.services.ConfirmationOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultConfirmationServiceTest {
    private var n = 0
    private fun TestScope.service() = DefaultConfirmationService(clock = { testScheduler.currentTime }, newId = { "c${n++}" })

    @Test fun firstAnswerWinsAndSecondIsIgnored() = runTest {
        val svc = service()
        val outcome = async { svc.ask(ConfirmationKind.TakeOverWorkout, "Take over?", "SH is tracking") }
        runCurrent()
        val id = svc.pending.value!!.id
        assertTrue(svc.answer(id, yes = true))      // e.g. glasses
        assertFalse(svc.answer(id, yes = false))    // watch, a moment later
        assertEquals(ConfirmationOutcome.Yes, outcome.await())
        assertNull(svc.pending.value)
    }

    @Test fun answerForAnotherIdIsIgnored() = runTest {
        val svc = service()
        val outcome = async { svc.ask(ConfirmationKind.TakeOverWorkout, "t", "m") }
        runCurrent()
        assertFalse(svc.answer("nope", yes = true))
        svc.answer(svc.pending.value!!.id, yes = false)
        assertEquals(ConfirmationOutcome.No, outcome.await())
    }

    @Test fun silenceTimesOutAfter15Seconds() = runTest {
        val svc = service()
        val outcome = async { svc.ask(ConfirmationKind.StopWorkoutByVoice, "End workout?", "") }
        runCurrent()
        assertEquals(15_000, svc.pending.value!!.expiresAtMs)
        advanceTimeBy(14_999); runCurrent()
        assertTrue(svc.pending.value != null)
        advanceTimeBy(2); runCurrent()
        assertEquals(ConfirmationOutcome.Timeout, outcome.await())
        assertNull(svc.pending.value)
    }

    @Test fun newAskSupersedesPendingOne() = runTest {
        val svc = service()
        val first = async { svc.ask(ConfirmationKind.TakeOverWorkout, "a", "") }
        runCurrent()
        val second = async { svc.ask(ConfirmationKind.StopWorkoutByVoice, "b", "") }
        runCurrent()
        assertEquals(ConfirmationOutcome.Superseded, first.await())
        assertEquals("b", svc.pending.value!!.title)
        svc.answer(svc.pending.value!!.id, yes = true)
        assertEquals(ConfirmationOutcome.Yes, second.await())
    }

    @Test fun cancelledAskClearsPendingAndRejectsLateAnswers() = runTest {
        val svc = service()
        val job = async { svc.ask(ConfirmationKind.TakeOverWorkout, "Take over?", "SH is tracking") }
        runCurrent()
        val id = svc.pending.value!!.id
        job.cancel()
        runCurrent()
        // After cancellation, pending should be cleared
        assertNull(svc.pending.value)
        // Late answer to the cancelled confirmation should be rejected
        assertFalse(svc.answer(id, yes = true))
    }
}
