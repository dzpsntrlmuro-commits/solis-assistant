package com.hellokittiy.launcher.notify

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hellokittiy.launcher.model.NotifItem

class KittiyNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
        broadcast()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        listeners.forEach { it.onNotificationsChanged(emptyList()) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        broadcast()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        broadcast()
    }

    fun currentNotifications(): List<NotifItem> {
        return try {
            activeNotifications
                ?.filter { !it.isOngoing && it.notification != null }
                ?.map { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras?.getCharSequence("android.title")?.toString()
                        ?: sbn.packageName
                    val text = extras?.getCharSequence("android.text")?.toString()
                        ?: extras?.getCharSequence("android.bigText")?.toString()
                        ?: ""
                    NotifItem(sbn.key, title, text, sbn.packageName)
                }
                ?.distinctBy { it.key }
                ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearAll() {
        try {
            cancelAllNotifications()
        } catch (_: Exception) {
            // ignore
        }
        broadcast()
    }

    private fun broadcast() {
        val list = currentNotifications()
        listeners.forEach { it.onNotificationsChanged(list) }
    }

    interface Listener {
        fun onNotificationsChanged(items: List<NotifItem>)
    }

    companion object {
        @Volatile
        var instance: KittiyNotificationListener? = null
            private set

        private val listeners = mutableSetOf<Listener>()

        fun addListener(listener: Listener) {
            listeners.add(listener)
            instance?.let { listener.onNotificationsChanged(it.currentNotifications()) }
        }

        fun removeListener(listener: Listener) {
            listeners.remove(listener)
        }
    }
}
