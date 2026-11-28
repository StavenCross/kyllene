package dev.hermeskotlin.ui.chat

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesktopFileStagingTest {
    @Test fun stagesACompleteFileUnderTheOpenerDirectoryAndClearsItOnRefusal() {
        val root = Files.createTempDirectory("file-open-test")
        val bytes = byteArrayOf(0, 45, -1)
        var staged: java.io.File? = null
        try {
            val result = runBlocking {
                openStagedFile(bytes, "../résumé.zip", root) { file ->
                    staged = file
                    assertEquals(root, file.toPath().parent.parent)
                    assertEquals("résumé.zip", file.name)
                    assertContentEquals(bytes, file.readBytes())
                    FileOpenResult.Cancelled
                }
            }
            assertEquals(FileOpenResult.Cancelled, result)
            assertFalse(checkNotNull(staged).parentFile.exists())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    // A Linux gateway allows names Windows can't write; ':' would even make an NTFS alternate stream.
    @Test fun windowsNamesAreMadeWritable() {
        assertEquals("report 10_30.txt", stagedFileName("report 10:30.txt", windows = true))
        assertEquals("a_b_c_.zip", stagedFileName("a<b>c?.zip", windows = true))
        assertEquals("notes", stagedFileName("notes. ", windows = true))
        assertEquals("_CON.txt", stagedFileName("CON.txt", windows = true))
        assertEquals("report 10:30.txt", stagedFileName("report 10:30.txt", windows = false))
    }

    @Test fun stagesAWholeFileWhoseNameHasAColon() {
        val root = Files.createTempDirectory("file-open-test")
        try {
            runBlocking {
                openStagedFile(byteArrayOf(1, 2, 3), "report 10:30.txt", root) { file ->
                    assertEquals(listOf(file.name), file.parentFile.list()?.toList())
                    assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
                    FileOpenResult.Cancelled
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
