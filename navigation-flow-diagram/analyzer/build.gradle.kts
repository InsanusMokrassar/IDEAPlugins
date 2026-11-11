plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
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
    // The IDE supplies PSI and the compiler; keep them out of the plugin artifact.
    compileOnly(kotlinCompiler)
    testImplementation(kotlinCompiler)
}
