package dev.inmo.idea.koin.dependency.navigation

import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.psi.KtExpression
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.plaf.basic.BasicHTML

class DiSiteRendererTest {
    @Test
    fun sourceLabelsRemainLiteralWithoutReadingPsi() {
        @Suppress("UNCHECKED_CAST")
        val pointer = Proxy.newProxyInstance(
            SmartPsiElementPointer::class.java.classLoader, arrayOf(SmartPsiElementPointer::class.java),
        ) { _, method, _ -> throw AssertionError("Rendering must not access PSI: ${method.name}") }
            as SmartPsiElementPointer<KtExpression>
        val label = "<html><b>source.kt</b> · Service"
        val site = DiSite(pointer, DiCall(DiKind.DEFINITION, setOf("Service"), DiQualifier.None, "Service"), 0, "source.kt", label)
        SwingUtilities.invokeAndWait {
            val renderer = DiSiteRenderer()
            renderer.getListCellRendererComponent(JList(), site, 0, false, false)
            renderer.preferredSize
            assertEquals(label, renderer.text)
            assertNull("Source text must not create Swing's HTML view", renderer.getClientProperty(BasicHTML.propertyKey))
            renderer.updateUI()
            assertNull(renderer.getClientProperty(BasicHTML.propertyKey))
        }
    }
}
