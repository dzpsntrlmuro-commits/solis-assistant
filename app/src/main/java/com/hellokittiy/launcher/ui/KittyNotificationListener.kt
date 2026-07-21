package com.hellokittiy.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hellokittiy.launcher.model.NotifItem
import java.util.concurrent.CopyOnWriteArrayList

class KittyNotificationListener : NotificationListenerService() {

    interface Listener {
        fun onNotificationsChanged(items: List<NotifItem>)
    }

    override fun onListenerConnected() {
        instance = this
        broadcast()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        broadcast()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        broadcast()
    }

    private fun broadcast() {
        val items = currentNotifications()
        listeners.forEach { it.onNotificationsChanged(items) }
    }

    companion object {
        @Volatile
        private var instance: KittyNotificationListener? = null
        private val listeners = CopyOnWriteArrayList<Listener>()

        fun addListener(listener: Listener) {
            listeners.addIfAbsent(listener)
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }

        fun isEnabled(context: Context): Boolean {
            val cn = ComponentName(context, KittyNotificationListener::class.java)
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.split(":").any {
                ComponentName.unflattenFromString(it)?.equals(cn) == true
            }
        }

        fun currentNotifications(): List<NotifItem> {
            val service = instance ?: return emptyList()
            return try {
                service.activeNotifications
                    ?.filter { !it.isOngoing }
                    ?.mapNotNull { sbn ->
                        val extras = sbn.notification.extras
                        val title = extras.getCharSequence("android.title")?.toString()
                            ?: sbn.packageName
                        val body = extras.getCharSequence("android.text")?.toString()
                            ?: extras.getCharSequence("android.bigText")?.toString()
                            ?: ""
                        if (title.isBlank() && body.isBlank()) null
                        else NotifItem(sbn.key, title, body, sbn.packageName)
                    }
                    ?.take(8)
                    ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun clearAll() {
            try {
                instance?.cancelAllNotifications()
            } catch (_: Exception) {
            }
        }
    }
}
