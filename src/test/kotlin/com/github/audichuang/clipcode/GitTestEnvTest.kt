package com.github.audichuang.clipcode

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GitTestEnvTest {
    @Test
    fun `fixture commands ignore hostile signing and hook settings`() {
        val hostile = Files.createTempDirectory("clipcode-hostile-git-home")
        try {
            val hostileXdg = hostile.resolve("xdg")
            val xdgGit = hostileXdg.resolve("git")
            Files.createDirectories(xdgGit)
            val config = "[commit]\n gpgsign = true\n[core]\n hooksPath = $hostile/hooks\n"
            val globalConfig = hostile.resolve(".gitconfig")
            val xdgConfig = xdgGit.resolve("config")
            Files.writeString(globalConfig, config)
            Files.writeString(xdgConfig, config)

            for (key in listOf("commit.gpgsign", "core.hooksPath")) {
                val process = ProcessBuilder("git", "config", "--get", key).apply {
                    environment()["HOME"] = hostile.toString()
                    environment()["XDG_CONFIG_HOME"] = hostileXdg.toString()
                    environment()["GIT_CONFIG_GLOBAL"] = globalConfig.toString()
                    environment()["GIT_CONFIG_SYSTEM"] = globalConfig.toString()
                    environment()["GIT_CONFIG_NOSYSTEM"] = "0"
                }.isolatedGitConfig().start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                assertEquals(1, process.waitFor(), "hostile $key must not leak into the fixture; output=$output")
                assertEquals("", output)
            }
        } finally {
            Files.walk(hostile).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
