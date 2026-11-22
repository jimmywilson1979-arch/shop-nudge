package com.jimmywilson.shopnudge

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor

/**
 * Shop map built weekly by GitHub from the OpenStreetMap UK extract
 * (see tools/build_shops.py). The UK is cut into 0.5° squares; the app
 * downloads only the squares around you and keeps them for a week.
 */
object TileSource {
    private const val BASE = "https://raw.githubusercontent.com/jimmywilson1979-arch/shop-nudge/shopdata/tiles/"
    private const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    private const val STEP = 0.5

    /** All squares touched by a circle of [radiusM] around the point. */
    fun keysAround(lat: Double, lon: Double, radiusM: Int): Set<String> {
        val dLat = radiusM / 111_000.0
        val dLon = radiusM / (111_000.0 * cos(Math.toRadians(lat)))
        val keys = LinkedHashSet<String>()
        for (i in floor((lat - dLat) / STEP).toInt()..floor((lat + dLat) / STEP).toInt()) {
            for (j in floor((lon - dLon) / STEP).toInt()..floor((lon + dLon) / STEP).toInt()) {
                keys += String.format(Locale.US, "%.1f_%.1f", i * STEP, j * STEP)
            }
        }
        return keys
    }

    /**
     * Every known shop within [radiusM]. Returns null if the map couldn't be
     * reached at all (so the caller can fall back to the live server).
     */
    fun shopsAround(ctx: Context, lat: Double, lon: Double, radiusM: Int): List<Shop>? {
        val dir = File(ctx.filesDir, "tiles").apply { mkdirs() }
        val out = ArrayList<Shop>()
        var gotAny = false
        for (k in keysAround(lat, lon, radiusM)) {
            val json = try {
                load(dir, k)
            } catch (e: FileNotFoundException) {
                gotAny = true          // no shops in that square (sea / outside UK)
                continue
            } catch (e: Exception) {
                continue
            }
            gotAny = true
            out += parse(json)
        }
        if (!gotAny) return null
        return out.filter { it.distanceTo(lat, lon) <= radiusM }.distinctBy { it.id }
    }

    private fun load(dir: File, key: String): String {
        val cached = File(dir, "$key.json")
        val fresh = cached.exists() && System.currentTimeMillis() - cached.lastModified() < MAX_AGE_MS
        if (fresh) return cached.readText()
        return try {
            val text = fetch(BASE + "$key.json")
            cached.writeText(text)
            text
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            if (cached.exists()) cached.readText() else throw e // offline: use last week's copy
        }
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "ShopNudge (+https://github.com/jimmywilson1979-arch/shop-nudge)")
            when (conn.responseCode) {
                200 -> return conn.inputStream.bufferedReader().use { it.readText() }
                404 -> throw FileNotFoundException(url)
                else -> throw IOException("Shop map said ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String): List<Shop> {
        val arr = JSONObject(json).optJSONArray("s") ?: return emptyList()
        val cats = Category.entries
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONArray(i) ?: return@mapNotNull null
            val mask = a.optInt(5)
            Shop(
                id = a.getString(0),
                name = a.optString(3),
                brand = a.optString(4),
                lat = a.getDouble(1),
                lon = a.getDouble(2),
                cats = cats.filter { mask and (1 shl it.ordinal) != 0 }.toSet(),
            )
        }
    }
}
