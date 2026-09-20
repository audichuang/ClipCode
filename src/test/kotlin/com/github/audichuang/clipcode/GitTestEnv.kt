package com.github.audichuang.clipcode

/**
 * Points `GIT_CONFIG_GLOBAL` / `GIT_CONFIG_SYSTEM` at a file that does not exist, which git
 * reads as an empty config.
 *
 * A path that is simply absent is used rather than `/dev/null` (or `NUL` on Windows)
 * precisely because those spellings are OS-specific — the thing this constant exists to
 * avoid. Nothing ever creates this file.
 *
 * Without the isolation, a developer's own `~/.gitconfig` leaks into every throwaway repo
 * these fixtures build: `commit.gpgsign = true` makes `git commit` invoke gpg with no TTY
 * and exit non-zero, and `core.hooksPath` runs foreign hooks inside the fixture. Both fail
 * on that person's machine only, which is the worst kind of failure to debug. It also pins
 * `core.autocrlf` and `init.defaultBranch` for free.
 */
internal val NUL_CONFIG: String =
    java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "clipcode-absent-gitconfig").toString()
