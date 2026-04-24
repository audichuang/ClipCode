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
