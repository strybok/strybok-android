package com.strybok.sdk

import android.database.Cursor
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A [Cursor] with [rows] of [columns] strings each. Only the four methods the source may use
 * work; any other call fails the test, which is how "no other column is read" is checked.
 */
fun fakeCursor(columns: Int, rows: List<List<String?>>, closed: AtomicInteger = AtomicInteger()): Cursor {
    var position = -1
    return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
        when (method.name) {
            "moveToFirst" -> {
                position = 0
                rows.isNotEmpty()
            }
            "getColumnCount" -> columns
            "getString" -> {
                val column = args[0] as Int
                check(column == 5) { "column $column was read" }
                rows[position][column]
            }
            "close" -> closed.incrementAndGet().let { }
            else -> throw AssertionError("unexpected call: ${method.name}")
        }
    } as Cursor
}

class InstallReferrerSourcesTest {
    @get:Rule
    val folder = TemporaryFolder()

    // --- Google Play and Galaxy Store ------------------------------------------------------

    @Test
    fun `the vendor libraries are not dependencies of the SDK`() {
        // compileOnly: they are on the compile classpath of the SDK and nowhere else, so these
        // tests run like an app that did not add them.
        assertFalse(PlayInstallReferrerSource.isLibraryAvailable())
        assertFalse(GalaxyStoreInstallReferrerSource.isLibraryAvailable())
    }

    @Test
    fun `maps the response codes of the vendor clients`() {
        fun answer(code: Int) = installReferrerAnswer(code) { "strybok_click=$CLICK_ID" }

        assertEquals("strybok_click=$CLICK_ID", answer(0).referrer)
        assertTrue("SERVICE_UNAVAILABLE", answer(1).isUnavailableForNow)
        assertTrue("SERVICE_DISCONNECTED", answer(-1).isUnavailableForNow)
        assertTrue("FEATURE_NOT_SUPPORTED", answer(2).isUnavailable)
        assertTrue("DEVELOPER_ERROR", answer(3).isUnavailable)
        assertTrue("PERMISSION_ERROR", answer(4).isUnavailable)
        assertTrue("a code from the future", answer(17).isUnavailableForNow)
    }

    @Test
    fun `an empty or missing referrer is still a referrer, and a remote exception is not`() {
        assertEquals("", installReferrerAnswer(0) { "" }.referrer)
        assertEquals("", installReferrerAnswer(0) { null }.referrer)
        // android.os.RemoteException in an app; any exception of the binder call here.
        assertTrue(installReferrerAnswer(0) { throw IllegalStateException("Service not connected") }.isUnavailableForNow)
        val read = AtomicInteger()
        installReferrerAnswer(1) { read.incrementAndGet().toString() }
        assertEquals("nothing is read unless the store said OK", 0, read.get())
    }

    // --- AppGallery ------------------------------------------------------------------------

    @Test
    fun `AppGallery returns column 5 of the first row and closes the cursor`() {
        val closed = AtomicInteger()
        val row = listOf("a", "b", "c", "d", "e", "strybok_click=$CLICK_ID", "g")
        val source = AppGalleryInstallReferrerSource { fakeCursor(7, listOf(row, row), closed) }

        assertEquals("strybok_click=$CLICK_ID", source.getInstallReferrer().referrer)
        assertEquals(1, closed.get())
    }

    @Test
    fun `AppGallery with an empty referrer column is an ordinary install`() {
        val source = AppGalleryInstallReferrerSource { fakeCursor(6, listOf(listOf("", "", "", "", "", null))) }
        assertEquals("", source.getInstallReferrer().referrer)
    }

    @Test
    fun `AppGallery without a usable answer is unavailable`() {
        val closed = AtomicInteger()
        val noProvider = AppGalleryInstallReferrerSource { null }
        val noRow = AppGalleryInstallReferrerSource { fakeCursor(6, emptyList(), closed) }
        val tooFewColumns = AppGalleryInstallReferrerSource {
            fakeCursor(5, listOf(listOf("a", "b", "c", "d", "e")), closed)
        }
        val refused = AppGalleryInstallReferrerSource { throw SecurityException("Permission Denial") }

        assertTrue(noProvider.getInstallReferrer().isUnavailable)
        assertTrue(noRow.getInstallReferrer().isUnavailable)
        assertTrue(tooFewColumns.getInstallReferrer().isUnavailable)
        assertTrue(refused.getInstallReferrer().isUnavailable)
        assertEquals("both cursors were closed", 2, closed.get())
    }

    @Test
    fun `any other AppGallery failure is unavailable for now`() {
        val broken = AppGalleryInstallReferrerSource { throw IllegalArgumentException("Unknown URI") }
        assertTrue(broken.getInstallReferrer().isUnavailableForNow)

        val closed = AtomicInteger()
        val failingRead = AppGalleryInstallReferrerSource {
            val real = fakeCursor(6, listOf(listOf("", "", "", "", "", "x")), closed)
            Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
                if (method.name == "getString") throw IllegalStateException("CursorWindow")
                method.invoke(real, *(args ?: emptyArray()))
            } as Cursor
        }
        assertTrue(failingRead.getInstallReferrer().isUnavailableForNow)
        assertEquals(1, closed.get())
    }

    // --- the two stored values -----------------------------------------------------------------

    @Test
    fun `stores attempts and done in a directory that is created on demand`() {
        val directory = File(folder.root, "no_backup/com.strybok.sdk/deferred")
        val files = DeferredLinkFiles { directory }
        assertEquals(0, files.attempts())
        assertFalse(files.isDone())
        assertFalse("reading creates nothing", directory.exists())

        assertTrue(files.storeAttempts(1))
        assertTrue(files.storeAttempts(2))
        assertEquals(2, files.attempts())
        assertFalse(files.isDone())
        assertTrue(files.storeDone())

        // A new process sees the same values.
        val later = DeferredLinkFiles { directory }
        assertEquals(2, later.attempts())
        assertTrue(later.isDone())
        assertEquals(setOf("attempts", "done"), directory.list()!!.toSet())
        assertEquals("2", File(directory, "attempts").readText())
    }

    @Test
    fun `an unreadable attempts value means no attempts are left`() {
        val files = DeferredLinkFiles { folder.root }
        File(folder.root, "attempts").writeText("not a number")
        assertEquals(Int.MAX_VALUE, files.attempts())
        File(folder.root, "attempts").writeText("-4")
        assertEquals(0, files.attempts())
    }

    @Test
    fun `reports when a value could not be stored`() {
        val none = DeferredLinkFiles { null }
        assertFalse(none.storeAttempts(1))
        assertFalse(none.storeDone())
        assertEquals(0, none.attempts())
        assertFalse(none.isDone())

        val blocked = DeferredLinkFiles { File(folder.newFile("a-file"), "below") }
        assertFalse(blocked.storeAttempts(1))
        assertFalse(blocked.storeDone())
        assertNull(File(folder.root, "a-file/below").list())
    }
}
