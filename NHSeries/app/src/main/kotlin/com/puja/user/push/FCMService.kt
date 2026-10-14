package com.puja.user.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.puja.user.MainActivity
import com.puja.user.R

/**
 * Handles Firebase Cloud Messaging for N𓆙G Series.
 *
 * Responsibilities:
 *  1. Receives a fresh FCM registration token whenever Firebase issues one
 *     (first install, app data clear, token rotation) and hands it to JS
 *     via [TokenBridgeHolder] so the WebView can persist it to Firebase
 *     Realtime Database (so the Admin app can target this device).
 *  2. Receives push messages sent by the Admin app via the FCM HTTP v1 API
 *     and shows a real Android system notification — works even if the
 *     app is closed or in the background.
 *
 * No existing feature, UI, or animation is touched by this file.
 */
class FCMService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "nh_series_notifications"
        const val PREFS_NAME = "nh_fcm_prefs"
        const val PREFS_KEY_TOKEN = "fcm_token"
        private var notifId = 1000

        /** Reads the last known FCM token from local storage, if any. */
        fun getStoredToken(context: Context): String? {
            val prefs: SharedPreferences =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(PREFS_KEY_TOKEN, null)
        }

        private fun storeToken(context: Context, token: String) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREFS_KEY_TOKEN, token).apply()
        }

        /** Creates the notification channel (Android 8+ requirement). Safe to call repeatedly. */
        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        "N𓆙G Series Notifications",
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Updates, new releases and offers from N𓆙G Series"
                        enableLights(true)
                        enableVibration(true)
                    }
                    manager.createNotificationChannel(channel)
                }
            }
        }

        /**
         * Fetches the current FCM token asynchronously and invokes [onToken] with it.
         * Used by the WebView bridge (AndroidApp.getFcmToken) so JS can save it to
         * Firebase Realtime Database under deviceTokens/{token}.
         */
        fun fetchToken(context: Context, onToken: (String?) -> Unit) {
            FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        val token = task.result
                        if (!token.isNullOrBlank()) storeToken(context, token)
                        onToken(token)
                    } else {
                        onToken(null)
                    }
                }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        storeToken(applicationContext, token)
        // The next time the WebView page is active, MainActivity will push this
        // fresh token to JS so it can be re-saved to Firebase. We don't reach
        // into the WebView directly from here since the Activity may not be alive.
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title
            ?: message.data["title"]
            ?: "N𓆙G Series"
        val body = message.notification?.body
            ?: message.data["body"]
            ?: ""

        showSystemNotification(title, body)
    }

    private fun showSystemNotification(title: String, body: String) {
        ensureChannel(applicationContext)

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(resources.getColor(R.color.accent, theme))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .build()

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notifId++, notification)
    }
}
