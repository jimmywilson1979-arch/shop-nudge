package com.jimmywilson.shopnudge

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofenceStatusCodes
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

/**
 * Uses Android's built-in geofencing: the phone itself watches up to 100 circles
 * and wakes the app only when you've been inside one for a couple of minutes.
 */
object GeofenceManager {
    const val SHOP_RADIUS_M = 120f
    const val AREA_RADIUS_M = 4_000f
    const val LOITER_MS = 120_000
    const val MAX_SHOPS = 95

    private fun pendingIntent(ctx: Context): PendingIntent {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx, 0, Intent(ctx, GeofenceReceiver::class.java), flags)
    }

    fun hasForeground(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Shops that sell something on the list, nearest first. */
    fun watchedShops(ctx: Context): List<Shop> {
        val c = Store.center(ctx) ?: return emptyList()
        val items = Store.items(ctx)
        if (items.isEmpty()) return emptyList()
        return Store.shops(ctx)
            .filter { s -> items.any { it.matches(s) } }
            .sortedBy { it.distanceTo(c.first, c.second) }
            .take(MAX_SHOPS)
    }

    @SuppressLint("MissingPermission")
    fun registerFromCache(ctx: Context) {
        val app = ctx.applicationContext
        val client = LocationServices.getGeofencingClient(app)
        val pi = pendingIntent(app)
        val center = Store.center(app)
        val shops = watchedShops(app)

        client.removeGeofences(pi).addOnCompleteListener {
            if (center == null || shops.isEmpty() || !hasForeground(app) || !hasBackground(app)) return@addOnCompleteListener
            val fences = shops.map { s ->
                Geofence.Builder()
                    .setRequestId("shop:${s.id}")
                    .setCircularRegion(s.lat, s.lon, SHOP_RADIUS_M)
                    .setExpirationDuration(Geofence.NEVER_EXPIRE)
                    .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_DWELL)
                    .setLoiteringDelay(LOITER_MS)
                    .setNotificationResponsiveness(60_000)
                    .build()
            } + Geofence.Builder()
                .setRequestId("area")
                .setCircularRegion(center.first, center.second, AREA_RADIUS_M)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()

            val request = GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_DWELL)
                .addGeofences(fences)
                .build()
            try {
                client.addGeofences(request, pi)
                    .addOnSuccessListener {
                        if (Store.lastError(app)?.startsWith("Alerts") == true) Store.setError(app, null)
                    }
                    .addOnFailureListener { e ->
                        val code = (e as? ApiException)?.statusCode
                        Store.setError(
                            app,
                            if (code == GeofenceStatusCodes.GEOFENCE_NOT_AVAILABLE)
                                "Alerts need Location on, with Google Location Accuracy turned on (Settings › Location)."
                            else "Alerts couldn't be set: ${e.message}"
                        )
                    }
            } catch (e: SecurityException) {
                Store.setError(app, "Alerts need location set to 'Allow all the time'.")
            }
        }
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val ids = event.triggeringGeofences?.map { it.requestId } ?: return
        when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_EXIT -> if ("area" in ids) {
                // Left the area we searched — look for shops around the new spot.
                val loc = event.triggeringLocation
                RefreshWorker.enqueue(context, loc?.latitude, loc?.longitude)
            }
            Geofence.GEOFENCE_TRANSITION_DWELL -> ids.filter { it.startsWith("shop:") }
                .forEach { Alerts.atShop(context, it.removePrefix("shop:")) }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Android wipes geofences on restart / app update — put them back.
        GeofenceManager.registerFromCache(context)
        RefreshWorker.enqueue(context)
    }
}
