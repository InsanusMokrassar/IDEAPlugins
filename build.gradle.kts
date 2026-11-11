import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.inmo"
version = "1.0.3"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

val fixtureLibraries by configurations.creating

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdea("2026.3")
        bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }
    testImplementation("junit:junit:4.13.2")
    fixtureLibraries("io.insert-koin:koin-core-jvm:4.2.2")
    fixtureLibraries("dev.inmo:micro_utils.koin-jvm:0.30.1")
}

kotlin { jvmToolchain(25) }

intellijPlatform {
    pluginConfiguration {
        name = "Koin DI Navigation"
        ideaVersion {
            sinceBuild = "263.7307"
            // Analysis API evolves between IDE branches; only the tested 263 branch is supported.
            untilBuild = "263.*"
        }
    }
    pluginVerification {
        ides { current() }
    }
}

// Use the dedicated option so Gradle --offline cannot duplicate a CLI flag.
// All required dependencies are bundled with the selected IDE.
tasks.verifyPlugin { offline.set(true) }

tasks.test {
    maxHeapSize = "3g"
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    // Unrelated Ultimate plugins may have obfuscated class-name collisions in fixture's shared loader.
    systemProperty("idea.load.plugins.id", "dev.inmo.koin.di.navigation,org.jetbrains.kotlin,com.intellij.java")
    doFirst { systemProperty("di.fixture.libraries", fixtureLibraries.asPath) }
}
