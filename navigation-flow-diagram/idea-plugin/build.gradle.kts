plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.intellij.platform)
}

dependencies { implementation(project(":navigation-flow-diagram:analyzer")) }

intellijPlatform {
    projectName = "navigation-flow-diagram"
    pluginConfiguration { name = "Navigation Flow Diagram" }
}

tasks.test {
    systemProperty("idea.load.plugins.id", "dev.inmo.idea.navigation.flow.diagram,org.jetbrains.kotlin,com.intellij.java")
}
