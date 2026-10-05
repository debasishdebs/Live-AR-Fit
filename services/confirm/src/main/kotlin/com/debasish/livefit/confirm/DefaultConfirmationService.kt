package com.debasish.livefit.confirm

import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class DefaultConfirmationService(
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timeoutMs: Long = 15_000,
) : ConfirmationService {

    private val _pending = MutableStateFlow<Confirmation?>(null)
    override val pending: StateFlow<Confirmation?> = _pending
    private var waiter: CompletableDeferred<ConfirmationOutcome>? = null

    override suspend fun ask(kind: ConfirmationKind, title: String, message: String, defaultYes: Boolean): ConfirmationOutcome {
        waiter?.complete(ConfirmationOutcome.Superseded)
        val id = newId()
        val deferred = CompletableDeferred<ConfirmationOutcome>()
        waiter = deferred
        _pending.value = Confirmation(id, kind, title, message, defaultYes = defaultYes, expiresAtMs = clock.nowMs() + timeoutMs)
        val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ConfirmationOutcome.Timeout
        if (_pending.value?.id == id) _pending.value = null
        if (waiter === deferred) waiter = null
        return outcome
    }

    override fun answer(confirmationId: String, yes: Boolean): Boolean {
        val current = _pending.value ?: return false
        if (current.id != confirmationId) return false
        _pending.value = null
        return waiter?.complete(if (yes) ConfirmationOutcome.Yes else ConfirmationOutcome.No) ?: false
    }
}
