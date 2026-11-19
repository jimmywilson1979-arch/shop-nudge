package com.jimmywilson.shopnudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreenshotReaderTest {

    @Test
    fun robinsonsScreenshotPicksProductTitle() {
        // Roughly what on-device text recognition sees in James's Robinsons screenshot.
        val blocks = listOf(
            listOf("ROBINSONS"),
            listOf("ESTD 1823"),
            listOf("20*"),
            listOf("NO", "ADDED", "SUGAR"),
            listOf("APPLE &", "BLACKCURRANT"),
            listOf("66ml"),
            listOf("Robinsons Mini Apple & Blackcurrant On-", "The-Go Squash"),
        )
        val c = ScreenshotReader.candidates(blocks)
        println(c)
        assertEquals("Robinsons Mini Apple & Blackcurrant On-The-Go Squash", c.first())
        assertFalse(c.any { it == "20*" || it == "66ml" })
    }

    @Test
    fun shopAppJunkIsDropped() {
        val blocks = listOf(
            listOf("20:02"),
            listOf("Search"),
            listOf("Heinz Baked Beanz 4 x 415g"),
            listOf("£3.50"),
            listOf("Add to trolley"),
            listOf("Clubcard Price"),
            listOf("Write a review"),
        )
        val c = ScreenshotReader.candidates(blocks)
        println(c)
        assertEquals(listOf("Heinz Baked Beanz 4 x 415g"), c)
    }
}
