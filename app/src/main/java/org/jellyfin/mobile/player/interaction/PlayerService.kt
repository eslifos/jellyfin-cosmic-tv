package org.jellyfin.mobile.player.interaction

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.Constants.VIDEO_PLAYER_NOTIFICATION_ID
import timber.log.Timber

class PlayerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = requireNotNull(notification) { "PlayerService started without a notification" }

        val foregroundServiceType = when {
            AndroidVersion.isAtLeastQ -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            else -> 0
        }

        ServiceCompat.startForeground(
            this,
            VIDEO_PLAYER_NOTIFICATION_ID,
            notification,
            foregroundServiceType,
        )
        starting = false

        // Stopped while it was starting: now that it is in the foreground it may stop
        if (stopWhenStarted) {
            stopWhenStarted = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }

        return START_NOT_STICKY
    }

    companion object {
        /**
         * The notification to show, handed over in memory (the service runs in the same process). Not in the start
         * intent: with the artwork of the item it can pass the size the system delivers (TransactionTooLargeException),
         * then the service never starts, never reaches the foreground, and the system ends the app.
         */
        @Volatile
        private var notification: Notification? = null

        /**
         * A start asked of the system that has not reached the foreground yet. It must reach it: a service started
         * with startForegroundService and stopped before calling startForeground makes the system end the app
         * (RemoteServiceException).
         */
        @Volatile
        private var starting = false

        /** Stop asked while starting: done once the service is in the foreground. */
        @Volatile
        private var stopWhenStarted = false

        fun start(context: Context, notification: Notification) {
            this.notification = notification
            try {
                stopWhenStarted = false
                starting = true
                ContextCompat.startForegroundService(context, Intent(context, PlayerService::class.java))
            } catch (e: IllegalStateException) {
                starting = false
                Timber.w(e, "Not allowed to start player foreground service")
            }
        }

        fun stop(context: Context) {
            if (starting) {
                stopWhenStarted = true
                return
            }
            context.stopService(Intent(context, PlayerService::class.java))
        }
    }
}
