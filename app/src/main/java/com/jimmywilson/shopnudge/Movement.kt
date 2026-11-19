package com.jimmywilson.shopnudge

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Knows whether James is walking, driving, cycling or still, using Android's
 * built-in motion detection (very low battery). Used to nudge him as he
 * walks past a shop, but never when he's driving past.
 */
object Movement {
    private val TYPES = listOf(
        DetectedActivity.WALKING,
        DetectedActivity.RUNNING,
        DetectedActivity.ON_BICYCLE,
        DetectedActivity.IN_VEHICLE,
        DetectedActivity.STILL,
    )

    fun hasPermission(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 29)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
        else true

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 1, Intent(ctx, MovementReceiver::class.java), flags)
    }

    fun register(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasPermission(app)) return
        val request = ActivityTransitionRequest(TYPES.map {
            ActivityTransition.Builder()
                .setActivityType(it)
                .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                .build()
        })
        try {
            ActivityRecognition.getClient(app).requestActivityTransitionUpdates(request, pendingIntent(app))
        } catch (_: SecurityException) {
        }
    }

    /** True if the last thing the phone saw him start doing was walking or running. */
    fun isOnFoot(ctx: Context): Boolean = hasPermission(ctx) &&
        Store.activity(ctx) in setOf(DetectedActivity.WALKING, DetectedActivity.RUNNING, DetectedActivity.ON_FOOT)
}

class MovementReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        result.transitionEvents
            .lastOrNull { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            ?.let { Store.setActivity(context, it.activityType) }
    }
}
