package dev.hermeskotlin.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import dev.hermeskotlin.ui.chat.FileOpenResult
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.connections.AbstractConnection
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlatpakFileOpenerTest {
    @Test fun opensTheCompleteFileThroughThePortal() = fixture(0) { opener, file, portal ->
        assertEquals(FileOpenResult.Opened, opener.open(file))
        assertContentEquals(byteArrayOf(0, 45, -1), portal.read.get(2, TimeUnit.SECONDS))
        assertFalse(portal.closed.isDone)
    }

    @Test fun distinguishesChooserDismissalFromFailure() {
        fixture(1) { opener, file, _ -> assertEquals(FileOpenResult.Cancelled, opener.open(file)) }
        fixture(2) { opener, file, _ -> assertEquals(FileOpenResult.Failed, opener.open(file)) }
    }

    @Test fun anUnansweredRequestTimesOutAndCloses() = fixture(null) { opener, file, portal ->
        assertEquals(FileOpenResult.TimedOut, opener.open(file))
        portal.closed.get(2, TimeUnit.SECONDS)
    }

    @Test fun leavingTheViewerClosesThePendingRequest() = fixture(null, responseTimeoutMillis = 15_000) { opener, file, portal ->
        coroutineScope {
            val work = async(Dispatchers.IO) { opener.open(file) }
            try { portal.read.get(2, TimeUnit.SECONDS) } finally { withTimeout(2_000) { work.cancelAndJoin() } }
            assertTrue(work.isCancelled)
            portal.closed.get(2, TimeUnit.SECONDS)
        }
    }

    private fun fixture(
        code: Long?,
        responseTimeoutMillis: Long = 2_000,
        test: suspend (FlatpakFileOpener, java.io.File, Portal) -> Unit,
    ) {
        FlatpakPortalFixture().use { fixture ->
            fixture.bus.requestBusName("org.freedesktop.portal.Desktop")
            val portal = Portal(fixture.bus, code)
            fixture.bus.exportObject(portal)
            val path = Files.createTempFile("kyllene-file-ö-", ".zip")
            try {
                Files.write(path, byteArrayOf(0, 45, -1))
                runBlocking { test(FlatpakFileOpener(fixture.address, responseTimeoutMillis = responseTimeoutMillis), path.toFile(), portal) }
            } finally {
                Files.deleteIfExists(path)
            }
        }
    }

    private class Portal(
        private val bus: org.freedesktop.dbus.connections.impl.DBusConnection,
        private val code: Long?,
    ) : OpenFilePortal, ShortcutsPortal.Request {
        val read = CompletableFuture<ByteArray>()
        val closed = CompletableFuture<Unit>()
        override fun getObjectPath() = "/org/freedesktop/portal/desktop"
        override fun OpenFile(parent: String, fd: FileDescriptor, options: Map<String, Variant<*>>): DBusPath {
            val descriptor = fd.intFileDescriptor
            val bytes = ByteArray(3)
            val libc = Native.load("c", LibC::class.java)
            try {
                assertEquals(3L, libc.read(descriptor, bytes, 3))
                assertEquals(0L, libc.read(descriptor, ByteArray(1), 1))
                assertEquals(-1L, libc.write(descriptor, byteArrayOf(1), 1))
            } finally { libc.close(descriptor) }
            val sender = AbstractConnection.getCallInfo().source.substring(1).replace('.', '_')
            val path = "$objectPath/request/$sender/${options.getValue("handle_token").value}"
            bus.exportObject(path, this)
            if (code != null) bus.sendMessage(ShortcutsPortal.Request.Response(path, UInt32(code), emptyMap()))
            read.complete(bytes)
            return DBusPath(path)
        }

        override fun Close() { closed.complete(Unit) }
    }

    interface LibC : Library {
        fun read(fd: Int, bytes: ByteArray, count: Long): Long
        fun write(fd: Int, bytes: ByteArray, count: Long): Long
        fun close(fd: Int): Int
    }
}
