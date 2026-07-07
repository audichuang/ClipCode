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
