package net.jurgensen.vw270telemetry.system

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import net.jurgensen.vw270telemetry.Runtime
import net.jurgensen.vw270telemetry.collectors.AaStateCollector
import net.jurgensen.vw270telemetry.data.TelemetryEvent

class AaNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        Runtime.hub.emit(TelemetryEvent("aa", "notification_listener", "connected"))
        activeNotifications
            ?.filter { it.packageName == AaStateCollector.AA_PACKAGE }
            ?.forEach { emitNotification("existing", it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == AaStateCollector.AA_PACKAGE) emitNotification("posted", sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == AaStateCollector.AA_PACKAGE) emitNotification("removed", sbn)
    }

    private fun emitNotification(action: String, sbn: StatusBarNotification) {
        val n = sbn.notification
        val x = n.extras
        Runtime.hub.emit(
            TelemetryEvent(
                "aa_notification", "${sbn.id}_${sbn.tag ?: "none"}", action,
                sourceTimestampMs = sbn.postTime,
                attributes = mapOf(
                    "category" to n.category,
                    "flags" to n.flags,
                    "ongoing" to sbn.isOngoing,
                    "group" to sbn.groupKey,
                    "title" to x?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                    "text" to x?.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                    "sub_text" to x?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                )
            )
        )
    }
}
