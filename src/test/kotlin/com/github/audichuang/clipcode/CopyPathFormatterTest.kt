package com.github.audichuang.clipcode

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals

class CopyPathFormatterTest {
    @Test
    fun `displayPath and relativeFilterPath use project root when nested content root appears first`() {
        val projectRoot = Files.createTempDirectory("clipcode-copy-path-project")
        val moduleRoot = projectRoot.resolve("sample-util").createDirectories()
        val file = moduleRoot.resolve("src/main/Included.kt")
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(moduleRoot.systemIndependentPath(), projectRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals(
            "sample-util/src/main/Included.kt",
            CopyPathFormatter.displayPath(resolver, file.systemIndependentPath())
        )
        assertEquals(
            "sample-util/src/main/Included.kt",
            CopyPathFormatter.relativeFilterPath(resolver, file.systemIndependentPath())
        )
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')
}
