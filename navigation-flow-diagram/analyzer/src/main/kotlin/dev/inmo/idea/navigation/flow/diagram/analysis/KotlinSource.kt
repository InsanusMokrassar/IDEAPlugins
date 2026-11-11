package dev.inmo.idea.navigation.flow.diagram.analysis

import org.jetbrains.kotlin.psi.KtFile

/** A source file and the path that clients use to navigate back to it. */
data class KotlinSource(val path: String, val file: KtFile)
