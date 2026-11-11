plugins {
    java
    alias(libs.plugins.intellij.platform)
}
version = "0.1.2"

tasks.test {
    maxHeapSize = "2g"
    testLogging { events("failed", "skipped") }
}
