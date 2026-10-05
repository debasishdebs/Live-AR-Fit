package com.debasish.livefit.phone

import android.service.notification.NotificationListenerService

/** Empty on purpose: holding notification access is what unlocks MediaSessionManager.getActiveSessions(). */
class MediaListener : NotificationListenerService()
