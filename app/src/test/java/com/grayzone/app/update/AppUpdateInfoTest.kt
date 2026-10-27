package com.grayzone.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateInfoTest {

    private val sha = "ab".repeat(32)

    private fun text(
        code: String = "10200",
        name: String = "1.2.0",
        apk: String = "grayzone-1.2.0.apk",
        hash: String = sha,
        size: String = "15000000",
        notes: String = "### Added\n- In-app updates",
    ) = "format=1\nversionCode=$code\nversionName=$name\napk=$apk\nsha256=$hash\nsize=$size\nnotes:\n$notes\n"

    @Test
    fun `parses a release with notes`() {
        val info = AppUpdateInfo.parse(text())!!
        assertEquals(10200, info.versionCode)
        assertEquals("1.2.0", info.versionName)
        assertEquals("https://github.com/vishalpa8/grayzone/releases/download/v1.2.0/grayzone-1.2.0.apk", info.apkUrl)
        assertEquals("### Added\n- In-app updates", info.notes)
    }

    @Test
    fun `version code must match the version name`() {
        assertNull(AppUpdateInfo.parse(text(code = "10300")))
        assertEquals(10203, AppUpdateInfo.codeFor("1.2.3"))
        assertEquals(20000, AppUpdateInfo.codeFor("2.0.0"))
    }

    @Test
    fun `newer means a higher version code`() {
        val info = AppUpdateInfo.parse(text())!!
        assertTrue(info.isNewerThan(10102))
        assertFalse(info.isNewerThan(10200))
        assertFalse(info.isNewerThan(10300))
    }

    @Test
    fun `malformed or unsafe fields are refused`() {
        assertNull(AppUpdateInfo.parse(text(apk = "../evil.apk")))
        assertNull(AppUpdateInfo.parse(text(apk = "grayzone.zip")))
        assertNull(AppUpdateInfo.parse(text(name = "1.2")))
        assertNull(AppUpdateInfo.parse(text(hash = "xyz")))
        assertNull(AppUpdateInfo.parse(text(size = "0")))
        assertNull(AppUpdateInfo.parse(text().replace("format=1", "format=2")))
        assertNull(AppUpdateInfo.parse("<html>Not found</html>"))
        assertNull(AppUpdateInfo.parse(""))
    }

    @Test
    fun `notes are optional and may contain equals signs`() {
        assertEquals("", AppUpdateInfo.parse(text(notes = ""))!!.notes)
        assertEquals("a=b", AppUpdateInfo.parse(text(notes = "a=b"))!!.notes)
    }
}
