package com.jimmywilson.shopnudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSourceTest {
    @Test
    fun squaresMatchTheMapBuilder() {
        // Hay-on-Wye, 10 km: same naming as tools/build_shops.py ("52.0_-3.5").
        val keys = TileSource.keysAround(52.0743, -3.1265, 10_000)
        println(keys)
        assertTrue("52.0_-3.5" in keys)
        assertTrue("52.0_-3.0" in keys)   // Hereford side
        assertTrue(keys.size in 2..6)
    }

    @Test
    fun parsesShopsAndCategories() {
        val json = """{"v":1,"s":[["n2",52.05,-2.72,"B&M","B&M",16],["w100",52.05585,-2.7145,"Aldi","Aldi",1],["n5",52.0,-3.0,"Next","Next",0]]}"""
        val shops = TileSource.parse(json)
        assertEquals(3, shops.size)
        assertEquals(setOf(Category.BARGAIN), shops[0].cats)
        assertEquals(setOf(Category.SUPERMARKET), shops[1].cats)

        val squash = Item(1, "Squash", setOf(Category.SUPERMARKET, Category.BARGAIN), "", 0)
        val truffles = Item(2, "Truffles", emptySet(), "ALDI", 0)
        assertTrue(squash.matches(shops[0]) && squash.matches(shops[1]) && !squash.matches(shops[2]))
        assertTrue(truffles.matches(shops[1]) && !truffles.matches(shops[0]))

        val caldicott = Shop("n9", "Caldicott Bros Home Improvements", "", 52.3, -2.6, setOf(Category.DIY))
        assertTrue(!truffles.matches(caldicott))
        val bm = Item(3, "Candles", emptySet(), "B & M", 0)
        assertTrue(bm.matches(shops[0]))
        assertTrue(Item(4, "x", emptySet(), "b&m", 0).matches(Shop("n8", "B & M Bargains", "", 0.0, 0.0, emptySet())))
    }
}
