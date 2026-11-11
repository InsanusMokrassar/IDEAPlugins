import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformDependenciesExtension
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.tasks.BuildPluginTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.intellij.platform) apply false
}

allprojects { group = "dev.inmo" }

val platformVersion = providers.gradleProperty("platformVersion")
val pluginSinceBuild = providers.gradleProperty("pluginSinceBuild")
val pluginUntilBuild = providers.gradleProperty("pluginUntilBuild")
val pluginJavaVersion = providers.gradleProperty("pluginJavaVersion").map(String::toInt)
val localIdePath = providers.gradleProperty("localIdePath").map { rootProject.file(it).absolutePath }
val junit = libs.junit

val collectPluginZips = tasks.register<Sync>("collectPluginZips") {
    group = "build"
    description = "Build all three plugins and collect their current ZIPs in local.dists."
    into(layout.projectDirectory.dir("local.dists"))
    duplicatesStrategy = DuplicatesStrategy.FAIL
    // Replace obsolete ZIPs while leaving any other local files alone.
    preserve { exclude("*.zip") }
}

tasks.assemble { dependsOn(collectPluginZips) }

subprojects {
    repositories { mavenCentral() }

    pluginManager.withPlugin("java") {
        dependencies { add("testImplementation", junit) }
        val moduleCheck = tasks.named("check")
        rootProject.tasks.named("check") { dependsOn(moduleCheck) }
    }

    pluginManager.withPlugin("org.jetbrains.intellij.platform") {
        repositories { intellijPlatform { defaultRepositories() } }
        dependencies {
            extensions.configure<IntelliJPlatformDependenciesExtension> {
                if (localIdePath.isPresent) local(localIdePath.get()) else intellijIdea(platformVersion)
                bundledPlugins("com.intellij.java", "org.jetbrains.kotlin")
                testFramework(TestFrameworkType.Platform)
                testFramework(TestFrameworkType.Plugin.Java)
            }
        }
        extensions.configure<JavaPluginExtension> {
            toolchain.languageVersion = pluginJavaVersion.map(JavaLanguageVersion::of)
        }
        extensions.configure<IntelliJPlatformExtension> {
            // Each plugin has its own test/run sandbox even when built together.
            sandboxContainer = layout.buildDirectory.dir("idea-sandbox")
            pluginConfiguration.ideaVersion {
                sinceBuild = pluginSinceBuild
                untilBuild = pluginUntilBuild
            }
            pluginVerification { ides { current() } }
        }
        tasks.withType<Test>().configureEach {
            maxHeapSize = "3g"
            systemProperty("idea.kotlin.plugin.use.k2", "true")
        }
        // Check the selected SDK's bundled dependencies without Marketplace substitution.
        tasks.withType<VerifyPluginTask>().configureEach { offline.set(true) }
        tasks.named("check") { dependsOn("verifyPluginStructure", "verifyPlugin") }
        val archive = tasks.named<BuildPluginTask>("buildPlugin").flatMap { it.archiveFile }
        collectPluginZips.configure { from(archive) }
    }
}
