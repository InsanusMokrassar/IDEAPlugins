package dev.inmo.idea.koin.dependency.navigation

import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.*
import java.io.DataInput
import java.io.DataOutput

internal object DiIndexKeys {
    val NAME: ID<String, List<String>> = ID.create("dev.inmo.idea.koin.dependency.navigation.callNames")
    fun call(name: String) = "call:$name"
    fun derived(name: String) = "derived:$name"
}

/** Indexing is syntax-only. Resolution happens later in a cancellable smart read action. */
class DiCallIndex : FileBasedIndexExtension<String, List<String>>() {
    override fun getName() = DiIndexKeys.NAME
    override fun getVersion() = 3
    override fun dependsOnFileContent() = true
    override fun getInputFilter() = FileBasedIndex.InputFilter { it.fileType == KotlinFileType.INSTANCE }
    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE
    override fun getValueExternalizer() = object : DataExternalizer<List<String>> {
        override fun save(output: DataOutput, value: List<String>) {
            output.writeInt(value.size)
            value.forEach(output::writeUTF)
        }
        override fun read(input: DataInput) = List(input.readInt()) { input.readUTF() }
    }
    override fun getIndexer() = DataIndexer<String, List<String>, FileContent> { content ->
        val file = content.psiFile as? KtFile ?: return@DataIndexer emptyMap()
        val keys = mutableMapOf<String, MutableSet<String>>()
        file.importDirectives.forEach { directive ->
            val alias = directive.aliasName ?: return@forEach
            val original = directive.importedFqName?.shortName()?.asString() ?: return@forEach
            keys.getOrPut(DiIndexKeys.derived(original)) { linkedSetOf() }.add(alias)
        }
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                ProgressManager.checkCanceled()
                expression.calleeName()?.let { keys.putIfAbsent(DiIndexKeys.call(it), linkedSetOf()) }
                super.visitCallExpression(expression)
            }

            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                if (expression is KtNameReferenceExpression &&
                    generateSequence(expression.parent) { it.parent }.takeWhile { it !is KtFile }
                        .none { it is KtImportDirective || it is KtPackageDirective || it is KtUserType }) {
                    keys.putIfAbsent(DiIndexKeys.call(expression.getReferencedName()), linkedSetOf())
                }
                super.visitSimpleNameExpression(expression)
            }

            override fun visitNamedFunction(function: KtNamedFunction) {
                val name = function.name
                val delegate = function.forwardedCall()?.calleeName()
                if (name != null && delegate != null) {
                    keys.getOrPut(DiIndexKeys.derived(delegate)) { linkedSetOf() }.add(name)
                }
                super.visitNamedFunction(function)
            }

            override fun visitProperty(property: KtProperty) {
                val name = property.name
                val delegate = property.forwardedCall()?.calleeName()
                if (name != null && delegate != null) {
                    keys.getOrPut(DiIndexKeys.derived(delegate)) { linkedSetOf() }.add(name)
                }
                super.visitProperty(property)
            }
        })
        keys.mapValues { it.value.toList() }
    }
}

internal fun KtCallExpression.calleeName(): String? = (calleeExpression as? KtNameReferenceExpression)?.getReferencedName()

/** Deliberately bounded: no control flow, loops, multiple statements or arbitrary data-flow guessing. */
internal fun KtNamedFunction.forwardedCall(): KtCallExpression? {
    return bodyExpression.forwardedCall()
}

internal fun KtProperty.forwardedCall(): KtCallExpression? = getter?.bodyExpression.forwardedCall()

private fun KtExpression?.forwardedCall(): KtCallExpression? {
    val body = this ?: return null
    val expression = when (body) {
        is KtBlockExpression -> (body.statements.singleOrNull() as? KtReturnExpression)?.returnedExpression
        else -> body
    }
    return expression.asCall()
}

internal fun KtExpression?.asCall(): KtCallExpression? = when (this) {
    is KtCallExpression -> this
    is KtQualifiedExpression -> selectorExpression as? KtCallExpression
    is KtParenthesizedExpression -> expression.asCall()
    else -> null
}
