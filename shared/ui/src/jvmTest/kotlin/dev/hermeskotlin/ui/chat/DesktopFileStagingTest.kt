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
}
