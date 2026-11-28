package dev.hermeskotlin.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import dev.hermeskotlin.ui.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.koin.core.context.GlobalContext
import java.awt.Desktop
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

interface DesktopFileOpener {
    val stagingDirectory: Path
    suspend fun open(file: File): FileOpenResult
}

enum class FileOpenResult { Opened, Cancelled, Failed, TimedOut, Unconfirmed }

/** Save is the system's Save dialog; with no share sheet, Share opens the file in its default app. */
@Composable
actual fun rememberMediaActions(): MediaActions {
    val koin = GlobalContext.getOrNull()
    val scope = rememberCoroutineScope()
    return remember(koin, scope) { desktopMediaActions(koin?.getOrNull(), scope) }
}

private fun desktopMediaActions(opener: DesktopFileOpener?, scope: CoroutineScope): MediaActions =
    object : MediaActions {
        override suspend fun save(bytes: ByteArray, name: String): String =
            saveOutcome(bytes, name)?.text ?: getString(Res.string.desktop_save_cancelled)

        override suspend fun saveOutcome(bytes: ByteArray, name: String): SaveOutcome? {
            val title = getString(Res.string.desktop_save_title)
            val file = fromDialog { saveFile(title, name) }
                .getOrElse { return SaveOutcome(getString(Res.string.desktop_save_dialog_failed), saved = false) }
                ?: return null
            val written = withContext(Dispatchers.IO) { runCatching { file.writeBytes(bytes) } }
            return written.fold(
                { SaveOutcome(getString(Res.string.desktop_saved_to, file.path), saved = true) },
                { SaveOutcome(getString(Res.string.desktop_save_failed, it.message.toString()), saved = false) },
            )
        }

        override fun share(bytes: ByteArray, name: String) {
            scope.launch { shareFailure(bytes, name) }
        }

        override suspend fun shareFailure(bytes: ByteArray, name: String): String? {
            val result = withContext(Dispatchers.IO) {
                try {
                    openStagedFile(bytes, name, opener?.stagingDirectory) { file ->
                        if (opener != null) opener.open(file) else runCatching {
                            Desktop.getDesktop().open(file)
                            FileOpenResult.Opened
                        }.getOrDefault(FileOpenResult.Failed)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    FileOpenResult.Failed
                }
            }
            return when (result) {
                FileOpenResult.Opened, FileOpenResult.Cancelled -> null
                FileOpenResult.TimedOut -> getString(Res.string.chat_file_open_timed_out, name)
                FileOpenResult.Failed, FileOpenResult.Unconfirmed -> getString(Res.string.chat_file_open_failed, name)
            }
        }
    }

internal suspend fun openStagedFile(
    bytes: ByteArray,
    name: String,
    stagingDirectory: Path?,
    open: suspend (File) -> FileOpenResult,
): FileOpenResult {
    val directory = (if (stagingDirectory == null) Files.createTempDirectory("herald")
        else Files.createTempDirectory(stagingDirectory, "herald-open-")).toFile()
    val file = File(directory, stagedFileName(name))
    var keep = false
    try {
        file.writeBytes(bytes)
        currentCoroutineContext().ensureActive()
        // With no conclusive response a receiver may already have the file and still need to open it.
        keep = true
        return open(file).also { keep = it != FileOpenResult.Failed && it != FileOpenResult.Cancelled }
    } finally {
        if (keep || !file.delete() && file.exists()) {
            directory.deleteOnExit()
            file.deleteOnExit()
        } else if (!directory.delete()) {
            directory.deleteOnExit()
        }
    }
}

internal fun stagedFileName(name: String, windows: Boolean = System.getProperty("os.name").startsWith("Windows")): String {
    var basename = name.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl)
    // Windows can't write these characters, trailing dots or spaces, or a device name such as CON.
    if (windows) {
        basename = basename.map { if (it in "<>:\"|?*") '_' else it }.joinToString("").trimEnd('.', ' ')
        if (basename.substringBefore('.').uppercase().matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) basename = "_$basename"
    }
    if (basename.isNotBlank() && basename != "." && basename != ".." && basename.toByteArray().size <= 255) return basename
    val extension = basename.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,16}")) }
    return extension?.let { "attachment.$it" } ?: "attachment"
}
