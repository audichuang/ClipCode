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
        val moduleRoot = projectRoot.resolve("inv-svc-adv").createDirectories()
        val file = moduleRoot.resolve("src/main/Included.kt")
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(moduleRoot.systemIndependentPath(), projectRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals(
            "inv-svc-adv/src/main/Included.kt",
            CopyPathFormatter.displayPath(resolver, file.systemIndependentPath())
        )
        assertEquals(
            "inv-svc-adv/src/main/Included.kt",
            CopyPathFormatter.relativeFilterPath(resolver, file.systemIndependentPath())
        )
    }

    @Test
    fun `displayPathOrFallback prefers project relative path for external file under project root`() {
        val projectRoot = Files.createTempDirectory("clipcode-node-modules-project")
        val file = projectRoot.resolve("node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss")
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals(
            "node_modules/cub-lib-view-rootng/styles/cdk/_a11y-theme.scss",
            CopyPathFormatter.displayPathOrFallback(
                resolver,
                file.systemIndependentPath(),
                file.systemIndependentPath()
            )
        )
    }

    @Test
    fun `displayPathOrFallback keeps fallback for external file outside project root`() {
        val projectRoot = Files.createTempDirectory("clipcode-external-fallback-project")
        val resolver = ClipboardPathResolver.fromRootPaths(
            listOf(projectRoot.systemIndependentPath()),
            projectRoot.systemIndependentPath()
        )

        assertEquals(
            "library.jar!/com/acme/Lib.java",
            CopyPathFormatter.displayPathOrFallback(
                resolver,
                "/outside/cache/library.jar!/com/acme/Lib.class",
                "library.jar!/com/acme/Lib.java"
            )
        )
    }

    private fun Path.systemIndependentPath(): String = toString().replace('\\', '/')
}
