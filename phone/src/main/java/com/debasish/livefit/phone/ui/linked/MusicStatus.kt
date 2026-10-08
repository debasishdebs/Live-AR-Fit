package com.debasish.livefit.phone.ui.linked

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/** "Connected" needs a live YouTube Music session; without one, access may still be fine (YTM just isn't open). */
fun musicStatusLabel(sessionConnected: Boolean, hasAccess: Boolean): String = when {
    sessionConnected -> "Connected"
    hasAccess -> "Ready · open YouTube Music to connect"
    else -> "Needs notification access"
}

fun hasMusicAccess(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
