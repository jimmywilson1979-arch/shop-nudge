package com.jimmywilson.shopnudge

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject

/** Types of shop an item can be bought at, mapped to OpenStreetMap tags. */
enum class Category(val label: String, val emoji: String, val overpass: List<String>) {
    SUPERMARKET("Supermarket", "🛒", listOf("""nwr["shop"="supermarket"]""")),
    CORNER_SHOP("Corner shop", "🏪", listOf("""nwr["shop"="convenience"]""")),
    CHEMIST("Chemist", "💊", listOf("""nwr["shop"="chemist"]""", """nwr["amenity"="pharmacy"]""")),
    DIY("DIY / hardware", "🔨", listOf("""nwr["shop"~"^(doityourself|hardware|trade)$"]""")),
    BARGAIN("B&M / pound shop", "🏷️", listOf("""nwr["shop"~"^(variety_store|department_store|discount)$"]""")),
    PET("Pet shop", "🐾", listOf("""nwr["shop"="pet"]""")),
    PETROL("Petrol station", "⛽", listOf("""nwr["amenity"="fuel"]"""));

    companion object {
        fun fromTags(tags: JSONObject): Set<Category> {
            val shop = tags.optString("shop")
            val amenity = tags.optString("amenity")
            val out = mutableSetOf<Category>()
            when (shop) {
                "supermarket" -> out += SUPERMARKET
                "convenience" -> out += CORNER_SHOP
                "chemist" -> out += CHEMIST
                "doityourself", "hardware", "trade" -> out += DIY
                "variety_store", "department_store", "discount" -> out += BARGAIN
                "pet" -> out += PET
            }
            if (amenity == "pharmacy") out += CHEMIST
            if (amenity == "fuel") out += PETROL
            return out
        }
    }
}

data class Item(
    val id: Long,
    val text: String,
    val cats: Set<Category>,
    val brand: String,
    val added: Long,
) {
    fun matches(shop: Shop): Boolean =
        if (brand.isNotBlank()) shop.searchText.contains(brand.trim().lowercase())
        else cats.any { it in shop.cats }

    fun whereText(): String =
        if (brand.isNotBlank()) "📍 Only at ${brand.trim()}"
        else cats.sortedBy { it.ordinal }.joinToString("  ·  ") { "${it.emoji} ${it.label}" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("text", text)
        .put("cats", JSONArray(cats.map { it.name }))
        .put("brand", brand).put("added", added)

    companion object {
        fun fromJson(o: JSONObject): Item {
            val arr = o.optJSONArray("cats") ?: JSONArray()
            val cats = (0 until arr.length()).mapNotNull { i ->
                runCatching { Category.valueOf(arr.getString(i)) }.getOrNull()
            }.toSet()
            return Item(o.getLong("id"), o.getString("text"), cats, o.optString("brand"), o.optLong("added"))
        }
    }
}

data class Shop(
    val id: String,
    val name: String,
    val brand: String,
    val lat: Double,
    val lon: Double,
    val cats: Set<Category>,
) {
    val label: String get() = name.ifBlank { brand.ifBlank { cats.firstOrNull()?.label ?: "Shop" } }
    val searchText: String get() = "$name $brand".lowercase()

    fun distanceTo(lat2: Double, lon2: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(lat, lon, lat2, lon2, r)
        return r[0]
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("brand", brand)
        .put("lat", lat).put("lon", lon)
        .put("cats", JSONArray(cats.map { it.name }))

    companion object {
        fun fromJson(o: JSONObject): Shop {
            val arr = o.optJSONArray("cats") ?: JSONArray()
            val cats = (0 until arr.length()).mapNotNull { i ->
                runCatching { Category.valueOf(arr.getString(i)) }.getOrNull()
            }.toSet()
            return Shop(o.getString("id"), o.optString("name"), o.optString("brand"),
                o.getDouble("lat"), o.getDouble("lon"), cats)
        }
    }
}

/** A place where the app never nudges (home, work…). */
data class QuietPlace(val name: String, val lat: Double, val lon: Double) {
    fun contains(lat2: Double, lon2: Double): Boolean {
        val r = FloatArray(1)
        Location.distanceBetween(lat, lon, lat2, lon2, r)
        return r[0] <= RADIUS_M
    }
    companion object { const val RADIUS_M = 250f }
}

/** Simple on-phone storage. Nothing leaves the phone except the shop search. */
object Store {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shopnudge", Context.MODE_PRIVATE)

    // ---- list items
    fun items(ctx: Context): List<Item> {
        val raw = prefs(ctx).getString("items", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { Item.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun saveItems(ctx: Context, items: List<Item>) {
        prefs(ctx).edit().putString("items", JSONArray(items.map { it.toJson() }).toString()).apply()
    }

    fun addItem(ctx: Context, item: Item) = saveItems(ctx, items(ctx).filter { it.id != item.id } + item)

    fun removeItems(ctx: Context, ids: Collection<Long>) = saveItems(ctx, items(ctx).filter { it.id !in ids })

    // ---- cached shops around the last search point
    fun shops(ctx: Context): List<Shop> {
        val raw = prefs(ctx).getString("shops", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { Shop.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun saveShops(ctx: Context, lat: Double, lon: Double, shops: List<Shop>) {
        prefs(ctx).edit()
            .putString("shops", JSONArray(shops.map { it.toJson() }).toString())
            .putString("centerLat", lat.toString())
            .putString("centerLon", lon.toString())
            .putLong("lastRefresh", System.currentTimeMillis())
            .apply()
    }

    fun center(ctx: Context): Pair<Double, Double>? {
        val p = prefs(ctx)
        val lat = p.getString("centerLat", null)?.toDoubleOrNull() ?: return null
        val lon = p.getString("centerLon", null)?.toDoubleOrNull() ?: return null
        return lat to lon
    }

    fun lastRefresh(ctx: Context): Long = prefs(ctx).getLong("lastRefresh", 0)

    // ---- status / errors
    fun lastError(ctx: Context): String? = prefs(ctx).getString("lastError", null)
    fun setError(ctx: Context, msg: String?) = prefs(ctx).edit().putString("lastError", msg).apply()

    // ---- quiet places
    fun quietPlaces(ctx: Context): List<QuietPlace> {
        val raw = prefs(ctx).getString("quiet", null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                QuietPlace(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
            }.getOrNull()
        }
    }

    fun saveQuietPlaces(ctx: Context, places: List<QuietPlace>) {
        val arr = JSONArray(places.map { JSONObject().put("name", it.name).put("lat", it.lat).put("lon", it.lon) })
        prefs(ctx).edit().putString("quiet", arr.toString()).apply()
    }

    fun addQuietPlace(ctx: Context, p: QuietPlace) = saveQuietPlaces(ctx, quietPlaces(ctx) + p)

    fun isQuiet(ctx: Context, lat: Double, lon: Double): Boolean = quietPlaces(ctx).any { it.contains(lat, lon) }

    // ---- walking / driving (from Movement)
    fun activity(ctx: Context): Int = prefs(ctx).getInt("activity", -1)
    fun setActivity(ctx: Context, type: Int) =
        prefs(ctx).edit().putInt("activity", type).putLong("activityAt", System.currentTimeMillis()).apply()

    fun lastItemNudge(ctx: Context, itemId: Long): Long = prefs(ctx).getLong("walk_$itemId", 0)
    fun setLastItemNudge(ctx: Context, itemIds: Collection<Long>) {
        val e = prefs(ctx).edit()
        val now = System.currentTimeMillis()
        itemIds.forEach { e.putLong("walk_$it", now) }
        e.apply()
    }

    // ---- don't nag: one alert per shop every few hours
    fun lastAlert(ctx: Context, shopId: String): Long = prefs(ctx).getLong("alert_$shopId", 0)
    fun setLastAlert(ctx: Context, shopId: String) =
        prefs(ctx).edit().putLong("alert_$shopId", System.currentTimeMillis()).apply()
}
