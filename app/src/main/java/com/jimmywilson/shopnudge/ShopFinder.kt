package com.jimmywilson.shopnudge

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Finds real shops around a point using OpenStreetMap (Overpass API) — free, no key.
 * Only asks for the kinds of shop the current list actually needs.
 */
object ShopFinder {
    const val RADIUS_M = 10_000

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    fun buildQuery(items: List<Item>, lat: Double, lon: Double, radius: Int = RADIUS_M): String {
        val around = String.format(Locale.US, "(around:%d,%.5f,%.5f)", radius, lat, lon)
        val lines = LinkedHashSet<String>()
        items.filter { it.brand.isBlank() }.flatMap { it.cats }.toSet().forEach { c ->
            c.overpass.forEach { lines += "$it$around;" }
        }
        items.map { it.brand.trim() }.filter { it.isNotEmpty() }.toSet().forEach { b ->
            val safe = b.replace(Regex("[^A-Za-z0-9 &'-]"), "").trim()
            if (safe.isNotEmpty()) {
                lines += "nwr[\"shop\"][\"name\"~\"$safe\",i]$around;"
                lines += "nwr[\"shop\"][\"brand\"~\"$safe\",i]$around;"
                lines += "nwr[\"amenity\"~\"^(pharmacy|fuel)$\"][\"name\"~\"$safe\",i]$around;"
            }
        }
        return "[out:json][timeout:25];\n(\n" + lines.joinToString("\n") + "\n);\nout center;"
    }

    fun find(items: List<Item>, lat: Double, lon: Double): List<Shop> {
        val query = buildQuery(items, lat, lon)
        var last: Exception? = null
        for (ep in endpoints) {
            try {
                return parse(post(ep, query))
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No shop server available")
    }

    fun parse(json: String): List<Shop> {
        val arr = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Shop>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val tags = o.optJSONObject("tags") ?: continue
            val center = o.optJSONObject("center")
            val lat = when {
                o.has("lat") -> o.getDouble("lat")
                center != null -> center.getDouble("lat")
                else -> continue
            }
            val lon = when {
                o.has("lon") -> o.getDouble("lon")
                center != null -> center.getDouble("lon")
                else -> continue
            }
            val id = o.optString("type", "x").take(1) + o.optLong("id")
            out += Shop(id, tags.optString("name"), tags.optString("brand"), lat, lon, Category.fromTags(tags))
        }
        return out.distinctBy { it.id }
    }

    private fun post(url: String, query: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 40_000
            conn.setRequestProperty("User-Agent", "ShopNudge/1.0 (personal shopping reminder app)")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw IOException("Shop server said $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
