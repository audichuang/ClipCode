package com.github.audichuang.clipcode

import java.nio.file.Files

/** Give fixture Git processes an empty home without Git 2.32-only config-path overrides. */
private val isolatedHome = Files.createTempDirectory("clipcode-git-home").toFile().also { it.deleteOnExit() }.path

internal fun ProcessBuilder.isolatedGitConfig(): ProcessBuilder = apply {
    environment().apply {
        remove("GIT_CONFIG_GLOBAL")
        remove("GIT_CONFIG_SYSTEM")
        this["HOME"] = isolatedHome
        this["XDG_CONFIG_HOME"] = isolatedHome
        this["GIT_CONFIG_NOSYSTEM"] = "1"
    }
}
