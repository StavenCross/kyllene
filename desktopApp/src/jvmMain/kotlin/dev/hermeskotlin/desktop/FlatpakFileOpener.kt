package dev.hermeskotlin.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import dev.hermeskotlin.ui.chat.DesktopFileOpener
import dev.hermeskotlin.ui.chat.FileOpenResult
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.FileDescriptor
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.CallbackHandler
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

@DBusInterfaceName("org.freedesktop.portal.OpenURI")
internal interface OpenFilePortal : DBusInterface {
    fun OpenFile(parent: String, fd: FileDescriptor, options: Map<String, Variant<*>>): DBusPath
}

/** A Flatpak grants the desktop access to this one staged file through the OpenURI portal. */
internal class FlatpakFileOpener(
    private val busAddress: String? = null,
    private val responseTimeoutMillis: Long = 120_000,
) : DesktopFileOpener {
    // Unlike sandbox-private /tmp, Flatpak's per-app runtime directory is visible to the host portal.
    override val stagingDirectory: Path
        get() = Path.of(System.getenv("XDG_RUNTIME_DIR"), "app", System.getenv("FLATPAK_ID"))

    override suspend fun open(file: File): FileOpenResult = try {
        runInterruptible(Dispatchers.IO) { openBlocking(file) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: TimeoutException) {
        FileOpenResult.TimedOut
    } catch (_: Exception) {
        FileOpenResult.Failed
    }

    private fun openBlocking(file: File): FileOpenResult {
        val builder = busAddress?.let(DBusConnectionBuilder::forAddress) ?: DBusConnectionBuilder.forSessionBus()
        builder.withShared(false).transportConfig().withTimeout(CALL_TIMEOUT.toInt())
        val fd = libc.open(file.absolutePath, O_CLOEXEC)
        if (fd < 0) return FileOpenResult.Failed
        // The descriptor outlives the bus so the sender cannot pass a recycled number after cancellation.
        try {
            return builder.build().use { bus ->
                if (!bus.isFileDescriptorSupported) return FileOpenResult.Failed
                val daemon = bus.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                if (!bus.call<Boolean>(daemon, "NameHasOwner", NAME)) {
                    bus.call<UInt32>(daemon, "StartServiceByName", NAME, UInt32(0))
                }
                val owner = bus.call<String>(daemon, "GetNameOwner", NAME)
                val token = "kyllene" + UUID.randomUUID().toString().replace("-", "")
                val path = "$PATH/request/${bus.uniqueName.substring(1).replace('.', '_')}/$token"
                val response = CompletableFuture<UInt32>()
                val portal = bus.getRemoteObject(owner, PATH, OpenFilePortal::class.java)
                val subscription = bus.addSigHandler(ShortcutsPortal.Request.Response::class.java, owner) {
                    if (it.path == path) response.complete(it.response)
                }
                var terminal = false
                var request = path
                subscription.use {
                    try {
                        request = bus.call<DBusPath>(portal, "OpenFile", "", FileDescriptor(fd), mapOf("handle_token" to Variant(token))).path
                        check(request == path)
                        val result = response.get(responseTimeoutMillis, TimeUnit.MILLISECONDS)
                        terminal = true
                        when (result.toLong()) {
                            0L -> FileOpenResult.Opened
                            1L -> FileOpenResult.Cancelled
                            else -> FileOpenResult.Failed
                        }
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (_: TimeoutException) {
                        FileOpenResult.TimedOut
                    } catch (_: Exception) {
                        FileOpenResult.Unconfirmed
                    } finally {
                        if (!terminal) {
                            val interrupted = Thread.interrupted()
                            try {
                                runCatching {
                                    val target = bus.getRemoteObject(owner, request, ShortcutsPortal.Request::class.java)
                                    bus.call<Void>(target, "Close", timeout = 500)
                                }
                            } finally { if (interrupted) Thread.currentThread().interrupt() }
                        }
                    }
                }
            }
        } finally {
            libc.close(fd)
        }
    }

    private fun <T> DBusConnection.call(target: DBusInterface, method: String, vararg args: Any, timeout: Long = CALL_TIMEOUT): T {
        val result = CompletableFuture<T>()
        callWithCallback(target, method, object : CallbackHandler<T> {
            override fun handle(value: T) { result.complete(value) }
            override fun handleError(error: DBusExecutionException) { result.completeExceptionally(error) }
        }, *args)
        return result.get(timeout, TimeUnit.MILLISECONDS)
    }

    internal interface LibC : Library {
        fun open(path: String, flags: Int): Int
        fun close(fd: Int): Int
    }

    private companion object {
        const val NAME = "org.freedesktop.portal.Desktop"
        const val PATH = "/org/freedesktop/portal/desktop"
        const val O_CLOEXEC = 0x80000 // O_RDONLY is zero.
        const val CALL_TIMEOUT = 5_000L
        val libc: LibC by lazy { Native.load("c", LibC::class.java) }
    }
}
