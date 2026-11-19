package com.jimmywilson.shopnudge

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/** Gets your location, finds matching shops nearby, and sets the alerts. */
class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val items = Store.items(ctx)
        if (items.isEmpty() || !GeofenceManager.hasForeground(ctx)) {
            GeofenceManager.registerFromCache(ctx)
            return Result.success()
        }

        var lat = inputData.getDouble("lat", Double.NaN)
        var lon = inputData.getDouble("lon", Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            val loc = currentLocation(ctx)
            if (loc == null) {
                GeofenceManager.registerFromCache(ctx)
                Store.setError(ctx, "Couldn't get your location just now — will try again.")
                return if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
            lat = loc.latitude
            lon = loc.longitude
        }

        return try {
            val shops = ShopFinder.find(items, lat, lon)
            Store.saveShops(ctx, lat, lon, shops)
            Store.setError(ctx, null)
            GeofenceManager.registerFromCache(ctx)
            Result.success()
        } catch (e: Exception) {
            Store.setError(ctx, "Shop search failed (${e.message ?: "no signal?"}) — will try again.")
            GeofenceManager.registerFromCache(ctx)
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }

    @SuppressLint("MissingPermission")
    private fun currentLocation(ctx: Context): Location? {
        val fused = LocationServices.getFusedLocationProviderClient(ctx)
        return try {
            Tasks.await(
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token),
                30, TimeUnit.SECONDS
            )
        } catch (e: Exception) {
            null
        } ?: try {
            Tasks.await(fused.lastLocation, 10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val UNIQUE = "refresh"

        private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(ctx: Context, lat: Double? = null, lon: Double? = null) {
            val data = Data.Builder()
            if (lat != null && lon != null) {
                data.putDouble("lat", lat)
                data.putDouble("lon", lon)
            }
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setInputData(data.build())
                .setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, req)
        }

        /** Safety net in case Android quietly drops the alerts. */
        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
