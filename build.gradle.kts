import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.inmo"
version = "1.0.2"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

val fixtureLibraries by configurations.creating

dependencies {
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath").orNull
        if (localIde != null) local(localIde) else intellijIdea("2026.2.4")
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
            sinceBuild = "262.11879"
            // Analysis API evolves between IDE branches; only the tested 262 branch is supported.
            untilBuild = "262.*"
        }
    }
    pluginVerification {
        ides { current() }
        // All required dependencies are bundled. Verify the actual selected IDE, without Marketplace substitution.
        freeArgs.add("-offline")
    }
}

tasks.test {
    maxHeapSize = "3g"
    systemProperty("idea.kotlin.plugin.use.k2", "true")
    // Unrelated Ultimate plugins may have obfuscated class-name collisions in fixture's shared loader.
    systemProperty("idea.load.plugins.id", "dev.inmo.koin.di.navigation,org.jetbrains.kotlin,com.intellij.java")
    doFirst { systemProperty("di.fixture.libraries", fixtureLibraries.asPath) }
}
