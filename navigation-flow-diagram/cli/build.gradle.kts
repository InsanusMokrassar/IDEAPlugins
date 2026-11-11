plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

val kotlinCompilerHome = providers.gradleProperty("kotlinCompilerHome").orNull
val kotlinCompiler = if (kotlinCompilerHome == null) libs.kotlin.compiler else {
    fileTree("$kotlinCompilerHome/lib") {
        include("*.jar")
        exclude("*-sources.jar")
    }
}

dependencies {
    implementation(project(":navigation-flow-diagram:analyzer"))
    implementation(kotlinCompiler)
}

application {
    mainClass = "dev.inmo.idea.navigation.flow.diagram.cli.MainKt"
    applicationName = "navigation-flow-diagram"
}
