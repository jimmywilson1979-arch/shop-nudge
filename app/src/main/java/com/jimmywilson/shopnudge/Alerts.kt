package com.jimmywilson.shopnudge

import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Alerts {
    const val CHANNEL = "at_shop"
    private const val COOLDOWN_MS = 3 * 60 * 60 * 1000L // one nudge per shop every 3 hours

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "At the shops", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Nudges when you're at a shop that sells something on your list"
                enableVibration(true)
            }
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /** Never nudge at home, work or any other quiet place. */
    private fun quietHere(ctx: Context, shop: Shop, lat: Double?, lon: Double?): Boolean =
        Store.isQuiet(ctx, lat ?: shop.lat, lon ?: shop.lon)

    fun atShop(ctx: Context, shopId: String, lat: Double?, lon: Double?) {
        val shop = Store.shops(ctx).find { it.id == shopId } ?: return
        if (quietHere(ctx, shop, lat, lon)) return
        val items = Store.items(ctx).filter { it.matches(shop) }
        if (items.isEmpty()) return
        if (System.currentTimeMillis() - Store.lastAlert(ctx, shopId) < COOLDOWN_MS) return
        Store.setLastAlert(ctx, shopId)
        show(ctx, "🛒 At ${shop.label}? Don't forget…", items, shopId.hashCode(), lat ?: shop.lat, lon ?: shop.lon)
    }

    private const val WALK_ITEM_COOLDOWN_MS = 45 * 60 * 1000L // don't re-nudge the same item at the next shop along

    fun passingShop(ctx: Context, shopId: String, lat: Double?, lon: Double?) {
        val shop = Store.shops(ctx).find { it.id == shopId } ?: return
        if (quietHere(ctx, shop, lat, lon)) return
        val now = System.currentTimeMillis()
        if (now - Store.lastAlert(ctx, shopId) < COOLDOWN_MS) return
        val items = Store.items(ctx).filter { it.matches(shop) }
            .filter { now - Store.lastItemNudge(ctx, it.id) > WALK_ITEM_COOLDOWN_MS }
        if (items.isEmpty()) return
        Store.setLastAlert(ctx, shopId)
        Store.setLastItemNudge(ctx, items.map { it.id })
        val dist = if (lat != null && lon != null) {
            val m = shop.distanceTo(lat, lon)
            if (m < 40) "is right here" else "is ${(m / 10).toInt() * 10} m away"
        } else "is nearby"
        show(ctx, "🚶 ${shop.label} $dist. Pop in for…", items, shopId.hashCode(), lat ?: shop.lat, lon ?: shop.lon)
    }

    @SuppressLint("MissingPermission")
    fun show(ctx: Context, title: String, items: List<Item>, notifId: Int, lat: Double? = null, lon: Double? = null) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        ensureChannel(ctx)

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val gotIt = PendingIntent.getBroadcast(
            ctx, notifId,
            Intent(ctx, GotItReceiver::class.java)
                .putExtra("ids", items.map { it.id }.toLongArray())
                .putExtra("notif", notifId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val style = NotificationCompat.InboxStyle()
        items.take(7).forEach { style.addLine("•  ${it.text}") }
        if (items.size > 7) style.setSummaryText("+${items.size - 7} more")

        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_basket)
            .setColor(ContextCompat.getColor(ctx, R.color.green))
            .setContentTitle(title)
            .setContentText(items.joinToString(", ") { it.text })
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, if (items.size == 1) "Got it ✓" else "Got them all ✓", gotIt)
            .apply {
                if (lat != null && lon != null) {
                    val quiet = PendingIntent.getBroadcast(
                        ctx, notifId + 1,
                        Intent(ctx, QuietReceiver::class.java)
                            .putExtra("lat", lat).putExtra("lon", lon).putExtra("notif", notifId),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                    addAction(0, "🔕 Quiet here", quiet)
                }
            }
            .build()
        try {
            nm.notify(notifId, n)
        } catch (_: SecurityException) {
        }
    }
}

/** "Got them" button on the notification ticks the items off. */
class GotItReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ids = intent.getLongArrayExtra("ids") ?: return
        Store.removeItems(context, ids.toList())
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notif", 0))
        GeofenceManager.registerFromCache(context)
    }
}

/** "Quiet here" button on a nudge: never nudge around this spot again. */
class QuietReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val lat = intent.getDoubleExtra("lat", Double.NaN)
        val lon = intent.getDoubleExtra("lon", Double.NaN)
        if (!lat.isNaN() && !lon.isNaN()) {
            val n = Store.quietPlaces(context).size + 1
            Store.addQuietPlace(context, QuietPlace("Quiet place $n", lat, lon))
        }
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra("notif", 0))
    }
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Alerts.ensureChannel(this)
        RefreshWorker.schedulePeriodic(this)
        Movement.register(this)
    }
}
