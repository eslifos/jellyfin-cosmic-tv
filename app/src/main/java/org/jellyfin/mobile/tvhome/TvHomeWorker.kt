package org.jellyfin.mobile.tvhome

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.jellyfin.mobile.app.ApiClientController
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Publishes the TV home screen (TvHomePublisher) in the background: every 6 hours, and soon after something that
 * changes it (the app connected or changed profile, the app left after watching, a playback stopped); or clears it
 * on signing out.
 */
class TvHomeWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {
    override suspend fun doWork(): Result {
        val publisher = TvHomePublisher(applicationContext, get())
        if (!publisher.isSupported) return Result.success()
        return try {
            if (inputData.getBoolean(KEY_CLEAR, false)) {
                publisher.clear()
            } else {
                // The session of the app (the worker may run with the app closed)
                get<ApiClientController>().loadSavedServerUser()
                publisher.publish()
            }
            Result.success()
        } catch (e: Exception) {
            Timber.w(e, "TV home: could not publish")
            Result.retry()
        }
    }

    companion object {
        private const val PERIODIC = "cosmic-tv-home"
        private const val ONCE = "cosmic-tv-home-now"
        private const val KEY_CLEAR = "clear"
        private const val PERIOD_HOURS = 6L
        private const val SOON_SECONDS = 5L

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Every 6 hours, kept across restarts (once is enough: it is not replaced). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TvHomeWorker>(PERIOD_HOURS, TimeUnit.HOURS).setConstraints(network).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Soon (several calls together are one publication). */
        fun refresh(context: Context) {
            val request = OneTimeWorkRequestBuilder<TvHomeWorker>()
                .setConstraints(network)
                .setInitialDelay(SOON_SECONDS, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }

        /** Signed out: everything published goes. */
        fun clear(context: Context) {
            val request = OneTimeWorkRequestBuilder<TvHomeWorker>().setInputData(workDataOf(KEY_CLEAR to true)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
