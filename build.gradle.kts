import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

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

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(17)
}

// Configure IntelliJ Platform Gradle Plugin
intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        id = properties("pluginGroup")
        name = properties("pluginName")
        version = properties("pluginVersion")
        description = """
            Copy File Content is a powerful IntelliJ IDEA plugin that enhances your workflow by allowing you to quickly copy
            and restore file contents with advanced filtering capabilities. Perfect for sharing code, working with AI assistants,
            and managing code snippets across projects.
        """.trimIndent()

        changeNotes = """
            <h2>Version 1.0.0 - Major Release</h2>
            <b>New Features:</b> Paste and Restore Files, Advanced Filtering System, Pattern Matching<br>
            <b>Improvements:</b> Platform 2024.3+ support, Enhanced UI, Better performance<br>
            <b>Bug Fixes:</b> Settings persistence, null safety, error handling<br>
            See full changelog at: https://github.com/mwguerra/copy-file-content
        """.trimIndent()

        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild").orNull
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
            recommended()
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
    wrapper {
        gradleVersion = properties("gradleVersion").get()
    }

}