package dev.yusuf.falconlauncher.widgets

import android.service.notification.NotificationListenerService

/** Exists only so the user can grant notification access, which unlocks reading media sessions. */
class MediaListener : NotificationListenerService()
