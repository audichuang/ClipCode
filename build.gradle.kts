import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.gradle.jvm.toolchain.JavaLanguageVersion

fun properties(key: String) = providers.gradleProperty(key)
fun environment(key: String) = providers.environmentVariable(key)

plugins {
    id("java") // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.gradleIntelliJPlugin) // Gradle IntelliJ Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin
}

group = properties("pluginGroup").get()
version = properties("pluginVersion").get()

// Configure project's dependencies
repositories {
    mavenCentral()
    
    // IntelliJ Platform Gradle Plugin Repositories Extension
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testCompileOnly("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.12.2")
    // Parses the shared cross-tool contract fixtures (clipboard-contract.json).
    testImplementation("com.google.code.gson:gson:2.11.0")

    // IntelliJ Platform Gradle Plugin Dependencies Extension
    intellijPlatform {
        create(properties("platformType"), properties("platformVersion"))

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file.
        plugins(properties("platformPlugins").map { it.split(',').map(String::trim).filter(String::isNotEmpty) })

        // Bundled plugins
        bundledPlugin("Git4Idea")

        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
}

val localIdeSmokePath = providers.gradleProperty("localIdeSmokePath")
    .orElse("/Applications/IntelliJ IDEA.app")

// Configure IntelliJ Platform Gradle Plugin
intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = properties("pluginGroup")
        name = properties("pluginName")
        version = properties("pluginVersion")
        description = """
            ClipCode - The ultimate code sharing tool for developers. Quickly copy file contents with smart formatting,
            perfect for sharing with AI assistants like ChatGPT, Claude, and Gemini. Features Git integration,
            paste-and-restore, and advanced filtering.
        """.trimIndent()

        changeNotes = """
            <h2>Version 1.2.8 - Complete node_modules text copying</h2>
            <ul>
                <li>Include modern JavaScript/TypeScript modules, source maps, and standard extensionless documentation when copying library roots.</li>
                <li>Honor the configured file-size limit for external libraries instead of silently applying an additional 10 MB cap.</li>
                <li>Stop recursive directory symlink cycles while preserving normal linked-package paths.</li>
            </ul>

            <h2>Version 1.2.7 - Batch history reads and responsive restore</h2>
            <ul>
                <li>Batch eligible historical Git content reads while retaining native caching, encoding, and conversion behavior.</li>
                <li>Restore large clipboard payloads in short, cancellable write commands so the IDE can process UI events between batches.</li>
                <li>Preserve overwrite and deletion Undo/Redo, including mixed line endings and binary files. Consecutive batches share Undo; intervening IDE commands can split the group.</li>
                <li>Avoid repeated Git add/remove prompts during restore without changing Git settings or staging files.</li>
            </ul>

            <h2>Version 1.2.6 - Responsive copying and empty-file preservation</h2>
            <ul>
              <li><b>Fixed:</b> Empty text files are preserved when copying files or folders and can be restored from the clipboard</li>
              <li><b>Fixed:</b> Cancelling a folder copy stops before the next file, including cancellation during the final file</li>
              <li><b>Improved:</b> Whole-payload notification statistics run off the UI thread to keep large copies responsive</li>
              <li><b>Compatibility:</b> Verification matrix includes IntelliJ IDEA 2025.2.6.1, 2025.3.4, 2026.1 and 2026.2.2</li>
              <li>This release does not claim to fix IntelliJ Git graph or macOS Metal rendering freezes</li>
            </ul>

            <h2>Version 1.2.5 - Reliable Git copying and faster selection</h2>
            <ul>
              <li><b>Fixed:</b> Staged files now copy index content instead of working-tree edits. Unstaged deletions use the index before-content; staged deletions use HEAD</li>
              <li><b>Fixed:</b> Git history selections no longer pick up unrelated editor files or become local changes merely because their paths match</li>
              <li><b>Improved:</b> Large Git selections read the local-change snapshot once, and duplicate paths no longer trigger repeated revision reads</li>
              <li><b>Fixed:</b> Cancelled copies stop cleanly. Git content decoding preserves UTF-16 text without adding a BOM and handles renamed files and deleted parent directories</li>
              <li><b>Fixed:</b> PR comparison failures are reported instead of appearing as empty diffs or a synchronized remote. Refresh also fetches for branches without an upstream</li>
              <li><b>Compatibility:</b> Removed deprecated API calls and unnecessary Kotlin interface bridges; verified with IntelliJ IDEA 2025.2.6.1 and 2026.2.2</li>
            </ul>

            <h2>Version 1.2.4 - Copy statistics that describe the clipboard</h2>
            <ul>
              <li><b>Fixed:</b> "Total characters", "Total lines" and "Total words" now measure the whole clipboard payload instead of only the raw file contents. They previously summed each file on its own, silently leaving out every <code>// file:</code> header, the blank line between files, the pre/post text and the root metadata line &mdash; everything that gets pasted but was never counted (about 2.8% of a five-file copy, roughly 1,500 characters at the 30-file limit). The estimated token count already measured the payload, so a single notification was reporting two different things</li>
              <li><b>Fixed:</b> Git and PR panel copies now report all four statistics; they previously showed the estimated token count alone</li>
              <li><b>Changed:</b> All four numbers are now shared with the Snipcode VS Code extension, which gained the three it was missing. The two tools report identical characters, lines, words and tokens for the same copy, pinned by the shared cross-tool test fixture and verified against 300,000 randomised inputs</li>
              <li>The four statistics come from one linear scan of the payload, so the notification costs one pass instead of several per-file ones</li>
            </ul>

            <h2>Version 1.2.3 - Faster, leaner copying</h2>
            <ul>
              <li><b>Fixed:</b> Copying a folder no longer keeps several copies of its contents in memory. Every directory level was concatenating its whole subtree's text — so memory grew with the content size multiplied by the folder depth — purely to produce three numbers for the copy notification. Those are now counted per file as it is read</li>
              <li><b>Fixed:</b> A large copy no longer freezes the IDE while its token count is estimated. The estimate used to split the entire payload into one string per word on the UI thread, precisely when the payload was big enough to deserve the size warning; it is now a single linear scan. The number itself is unchanged and still matches the Snipcode VS Code extension byte for byte</li>
              <li>Path handling no longer recompiles the same regular expressions for every file — one of them was recompiled for every path segment of every file. Filter rules and wildcard patterns are now prepared once per copy instead of once per file</li>
              <li><b>Changed:</b> For a folder copy the notification's "Total lines" and "Total words" now match what copying those same files individually reports; the previous figures lost one line at every file boundary. The clipboard content and the estimated token count are unaffected</li>
            </ul>

            <h2>Version 1.2.2 - Accurate token estimate, size warnings</h2>
            <ul>
              <li><b>Fixed:</b> The estimated token count in the copy notification now measures the whole clipboard payload — headers, pre/post text and the root metadata line included — instead of only the raw file contents. It previously disagreed with the number the ClipCode VS Code extension (Snipcode) reported for the very same copy; both tools now always report the identical figure, pinned by a shared cross-tool test fixture</li>
              <li><b>New:</b> Oversized copies are now colour-coded — the notification turns into a warning past 1,000,000 estimated tokens and an error past 2,000,000, so a payload too large for an AI assistant gets noticed before it is pasted</li>
              <li><b>Fixed:</b> Git and PR panel copies report their token count too; previously the same files reported statistics from the explorer but none at all when copied as changes</li>
            </ul>

            <h2>Version 1.2.1 - Cross-tool format alignment</h2>
            <ul>
              <li><b>Fixed:</b> Paste and Restore no longer treats a lone carriage return inside file content as a line break, matching the ClipCode VS Code extension (Snipcode) parser exactly</li>
              <li>Clipboard wire-format assembly is now unified behind a single formatter and pinned to the VS Code extension by a shared cross-tool contract test suite, so files copied in one tool keep restoring byte-for-byte in the other</li>
            </ul>

            <h2>Version 1.2.0 - PR panel, folder-level alignment, tighter VS Code interop</h2>
            <ul>
              <li><b>New:</b> ClipCode PR tool window — pick a base branch, review the base...HEAD diff, and copy the full changed sources in one click (with an origin behind-check)</li>
              <li><b>New:</b> Copies now include a leading <code>// clipcode-root: &lt;folder&gt;</code> metadata line (single-root projects), matching the ClipCode VS Code extension (Snipcode)</li>
              <li><b>New:</b> Paste and Restore detects when the copied paths are off by one folder level (copied from a parent folder, or into one) and offers to adjust all paths before restoring — deterministically via the metadata line, or via an on-disk heuristic for older clipboards</li>
              <li><b>Fixed:</b> Deleted-file markers, size-skip notices, and read-error notices in Git copies are now escaped like real content, so a custom permissive header format can no longer turn them into phantom files on restore</li>
              <li><b>Fixed:</b> Five reliability fixes across copy, settings, and Git paths</li>
              <li>Cross-tool contract with Snipcode locked down by new tests: deleted entries carrying old file content still restore as deletions, and the metadata line can never become a phantom file</li>
            </ul>

            <h2>Version 1.1.10 - Reliable copy/restore round-trip</h2>
            <ul>
              <li><b>Fixed:</b> A file whose own content contains a line like <code>// file: ...</code> now round-trips correctly — such lines are escaped on copy and restored verbatim on paste, instead of being split into a phantom file</li>
              <li><b>Fixed:</b> Paste and Restore preserves a file's own leading indentation and interior blank lines; only the structural separators the format inserts between files are trimmed</li>
              <li>Clipboard format stays compatible with the ClipCode VS Code extension (Snipcode) — both sides were updated together</li>
            </ul>

            <h2>Version 1.1.8 - Safer paste header parsing</h2>
            <ul>
              <li><b>Fixed:</b> Paste and Restore no longer treats JavaScript object properties such as <code>file: undefined,</code> as ClipCode file headers, preventing restored bundles from being truncated</li>
              <li><b>Fixed:</b> Paste and Restore only accepts custom file headers when the whole line matches, so strings containing <code>// file:</code> inside source content no longer split files unexpectedly</li>
            </ul>

            <h2>Version 1.1.7 - Wrapper root paste path detection</h2>
            <ul>
              <li><b>Fixed:</b> Paste and Restore now prefers an existing top-level child directory under the current project root, such as inv-web-console, before falling back to node_modules for detached Windows absolute paths</li>
            </ul>

            <h2>Version 1.1.6 - Paste Windows node_modules paths</h2>
            <ul>
              <li><b>Fixed:</b> Paste and Restore now accepts Windows absolute clipboard paths that point into node_modules packages, safely restoring them under the current project's node_modules directory when no project-root suffix is available</li>
            </ul>

            <h2>Version 1.1.5 - Project-relative node_modules copy paths</h2>
            <ul>
              <li><b>Fixed:</b> Files under project roots, including node_modules packages that IntelliJ marks as libraries, now copy with project-relative headers so Paste and Restore can resolve them reliably</li>
            </ul>

            <h2>Version 1.1.4 - Windows path casing compatibility</h2>
            <ul>
              <li><b>Fixed:</b> Paste and Restore resolves copied Windows absolute paths even when IDE project roots use different casing than the copied path</li>
            </ul>

            <h2>Version 1.1.3 - Restore Windows absolute paths</h2>
            <ul>
              <li><b>Fixed:</b> Paste and Restore now resolves copied Windows absolute paths from another machine when they point into a nested content root</li>
            </ul>

            <h2>Version 1.1.2 - Multi-module edge case fixes</h2>
            <ul>
              <li><b>Fixed:</b> Copy All Open Tabs and default copy headers now use project-root-relative paths in multi-module projects</li>
              <li><b>Fixed:</b> PATH filters and settings path selection now use the primary project root instead of the first content root</li>
              <li><b>Fixed:</b> Paste no longer treats a nested module parent directory as ambiguous unless an actual duplicate file exists</li>
            </ul>

            <h2>Version 1.1.1 - Multi-module path resolution</h2>
            <ul>
              <li><b>Fixed:</b> Copy now emits project-root-relative paths to preserve module prefix in multi-module projects</li>
              <li><b>Fixed:</b> Paste correctly prefers the project root module when a clipboard path could match multiple locations</li>
              <li><b>Improved:</b> Confirmation dialog now separates "Create (new)" from "Overwrite (existing)" and shows the absolute target path</li>
              <li><b>Prevented:</b> Accidental data loss when an unrelated file with the same relative path exists outside the intended module</li>
            </ul>

            <h2>Version 1.1.0 - Major Refactor and Git Delete Reliability</h2>
            <ul>
              <li><b>Fixed:</b> Pasting a file marked as [DELETED] (from Git Log, staging area, or changes view) now reliably removes the file in the current working tree</li>
              <li><b>Fixed:</b> Git deleted-file clipboard paths now resolve consistently across project roots and modules</li>
              <li><b>Improved:</b> Paste and Restore runs in the background, keeping the UI responsive for large operations</li>
              <li><b>Improved:</b> Paste and Restore explains whether deleted targets were already absent, unresolved, or ambiguous</li>
              <li><b>Upgraded:</b> Build toolchain now targets Java 21 and IntelliJ IDEA 2025.2+</li>
              <li><b>Added:</b> Automated tests for clipboard parsing, path resolution, restore planning, and Git content resolution</li>
            </ul>

            <h2>Version 1.0.0 - Initial Release</h2>
            <p><b>ClipCode</b> - The ultimate code sharing tool for AI-assisted development!</p>

            <h3>Core Features</h3>
            <ul>
              <li><b>Smart Copy</b>: Copy single or multiple files/directories with customizable headers</li>
              <li><b>Copy All Open Tabs</b>: Quickly copy content from all open editor tabs</li>
              <li><b>Statistics</b>: Shows file count, lines, words, and estimated token count</li>
            </ul>

            <h3>Git Integration</h3>
            <ul>
              <li>Copy from Git Staging Area (staged/unstaged files)</li>
              <li>Copy from Git Changes view with change type labels ([NEW], [MODIFIED], [DELETED], [MOVED])</li>
              <li>Copy from Git Log/History window</li>
            </ul>

            <h3>Paste &amp; Restore</h3>
            <ul>
              <li>Restore files from clipboard content (Ctrl+Shift+Alt+V)</li>
              <li>Automatic directory structure creation</li>
              <li>Smart parsing of Git change labels</li>
              <li>Overwrite protection with confirmation dialog</li>
            </ul>

            <h3>Advanced Filtering</h3>
            <ul>
              <li>PATH filters: Include/exclude specific directories</li>
              <li>PATTERN filters: Wildcards for file names (e.g., *.java, test_*)</li>
              <li>Individual rule enable/disable</li>
              <li>File size and count limits</li>
            </ul>

            <h3>Customization</h3>
            <ul>
              <li>Configurable header format with ${"$"}FILE_PATH placeholder</li>
              <li>Pre/post text wrappers</li>
              <li>Notification preferences</li>
            </ul>

            <p><b>Compatibility:</b> IntelliJ IDEA 2025.2+ and all JetBrains IDEs</p>
        """.trimIndent()

        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild").map { it.ifEmpty { null } }.orNull
        }
    }

    signing {
        certificateChain = environment("CERTIFICATE_CHAIN")
        privateKey = environment("PRIVATE_KEY")
        password = environment("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = environment("PUBLISH_TOKEN")
        channels = properties("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2025.2.6.1")
            create(IntelliJPlatformType.IntellijIdea, "2025.3.4")
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
            create(IntelliJPlatformType.IntellijIdea, "2026.2.2")
        }
    }
}

intellijPlatformTesting {
    runIde.register("runIde2026_1Local") {
        localPath = file(localIdeSmokePath.get())
        sandboxDirectory = layout.buildDirectory.dir("idea-sandbox/local-2026.1")
        task {
            onlyIf("Local IntelliJ IDEA 2026.1 is available") {
                file(localIdeSmokePath.get()).exists()
            }
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = properties("pluginRepositoryUrl")
}

// Configure Gradle Kover Plugin - read more: https://github.com/Kotlin/kotlinx-kover#configuration
kover {
    reports {
        filters {
            excludes {
                // 排除純 Swing UI 設定面板（測試 ROI 過低，業界慣例）
                classes(
                    "com.github.audichuang.clipcode.CopyFileContentConfigurable",
                    "com.github.audichuang.clipcode.CopyFileContentConfigurable\$*"
                )
                // 排除背景 Task lambda（IntelliJ Task.Backgroundable 內部閉包，無法穩定測試）
                classes(
                    "com.github.audichuang.clipcode.*Action\$actionPerformed\$*",
                    "com.github.audichuang.clipcode.*Action\$continueOnEdt\$*",
                    "com.github.audichuang.clipcode.*Action\$handleResolvedEntries\$*",
                    "com.github.audichuang.clipcode.*Action\$copyResolvedEntries\$*",
                    "com.github.audichuang.clipcode.*Action\$copySelectedFilesWithoutGitMetadata\$*",
                    "com.github.audichuang.clipcode.*Action\$showExecutionNotifications\$*",
                    "com.github.audichuang.clipcode.*Action\$showOverwriteDialog\$*",
                    "com.github.audichuang.clipcode.*Action\$performCopyFilesContent\$*"
                )
                // 整體 IDE Action wrapper class（純 Action 入口，內部 logic 已抽到 helper class 並單獨測試）
                classes(
                    "com.github.audichuang.clipcode.CopyAllOpenTabsAction"
                )
                // 透過 @IdeBoundCode annotation 排除 IDE-only 程式碼路徑
                // （modal dialog、ProgressManager、IDE data context、git binary 等）
                annotatedBy("com.github.audichuang.clipcode.IdeBoundCode")
            }
        }
        total {
            xml {
                onCheck = true
            }
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
        doFirst {
            temporaryDir.mkdirs()
        }
    }

    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

}
