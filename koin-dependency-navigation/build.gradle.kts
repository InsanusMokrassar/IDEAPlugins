plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}
version = "1.0.4"

val fixtureLibraries by configurations.creating
dependencies {
    fixtureLibraries(libs.koin.core)
    fixtureLibraries(libs.micro.utils.koin)
}

intellijPlatform {
    projectName = "koin-dependency-navigation"
    pluginConfiguration { name = "Koin Dependency Navigation" }
}

tasks.test {
    // Unrelated Ultimate plugins can collide in the fixture's shared class loader.
    systemProperty("idea.load.plugins.id", "dev.inmo.idea.koin.dependency.navigation,org.jetbrains.kotlin,com.intellij.java")
    doFirst { systemProperty("di.fixture.libraries", fixtureLibraries.asPath) }
}
