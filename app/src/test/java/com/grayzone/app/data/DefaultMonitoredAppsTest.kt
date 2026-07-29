package com.grayzone.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultMonitoredAppsTest {

    @Test
    fun `already seeded returns null and does not re-add`() {
        val existing = emptySet<String>()
        val installed = setOf("com.instagram.android", "com.google.android.youtube")
        assertNull(DefaultMonitoredApps.seedIfNeeded(true, existing, installed))
    }

    @Test
    fun `first seed merges only installed defaults`() {
        val existing = setOf("com.twitter.android")
        val installed = setOf("com.instagram.android", "com.spotify.music")
        val result = DefaultMonitoredApps.seedIfNeeded(false, existing, installed)!!
        assertEquals(
            setOf("com.twitter.android", "com.instagram.android"),
            result
        )
        assertTrue("com.google.android.youtube" !in result)
    }

    @Test
    fun `first seed with both installed adds both`() {
        val installed = setOf(
            "com.instagram.android",
            "com.google.android.youtube",
            "com.android.settings"
        )
        val result = DefaultMonitoredApps.seedIfNeeded(false, emptySet(), installed)!!
        assertEquals(
            setOf("com.instagram.android", "com.google.android.youtube"),
            result
        )
    }

    @Test
    fun `first seed with none installed yields empty set still marking seed needed`() {
        val result = DefaultMonitoredApps.seedIfNeeded(false, emptySet(), emptySet())!!
        assertEquals(emptySet<String>(), result)
    }

    @Test
    fun `does not duplicate packages already monitored`() {
        val existing = setOf("com.instagram.android")
        val installed = setOf("com.instagram.android", "com.google.android.youtube")
        val result = DefaultMonitoredApps.seedIfNeeded(false, existing, installed)!!
        assertEquals(
            setOf("com.instagram.android", "com.google.android.youtube"),
            result
        )
    }
}
