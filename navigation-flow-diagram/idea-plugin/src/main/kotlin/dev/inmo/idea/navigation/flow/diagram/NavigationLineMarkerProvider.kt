package dev.inmo.idea.navigation.flow.diagram

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.kotlin.psi.KtFile
import javax.swing.JList

internal object NavigationIcons {
    val ALLOCATION = IconLoader.getIcon("/icons/allocation.svg", NavigationIcons::class.java)
    val VIEW = IconLoader.getIcon("/icons/view.svg", NavigationIcons::class.java)
}

class NavigationLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName() = "dev.inmo.navigation allocations"
    override fun getIcon() = NavigationIcons.ALLOCATION
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val leaves = elements.filter { it.firstChild == null }.toHashSet()
        for (file in elements.mapNotNull { it.containingFile as? KtFile }.distinct()) {
            if (DumbService.isDumb(file.project)) continue
            val service = NavigationService.getInstance(file.project)
            for (site in service.markers(file)) {
                val leaf = file.findElementAt(site.location.offset) ?: continue
                if (leaf !in leaves) continue
                result += LineMarkerInfo(
                    leaf, leaf.textRange,
                    if (site.kind == MarkerKind.ALLOCATIONS) NavigationIcons.ALLOCATION else NavigationIcons.VIEW,
                    { XmlStringUtil.escapeString(site.tooltip) },
                    GutterIconNavigationHandler<PsiElement> { event, _ ->
                        // Reject a menu based on offsets from before an edit.
                        val current = service.markers(file).firstOrNull { it.location.offset == site.location.offset }
                            ?: return@GutterIconNavigationHandler
                        val revision = service.snapshot?.revision ?: return@GutterIconNavigationHandler
                        if (current.targets.size == 1) service.open(current.targets.single(), revision)
                        else JBPopupFactory.getInstance().createPopupChooserBuilder(current.targets)
                            .setTitle(current.tooltip)
                            .setRenderer(NavigationTextRenderer<NavigationTarget> { it.label })
                            .setItemChosenCallback { service.open(it, revision) }.createPopup().show(RelativePoint(event))
                    }, GutterIconRenderer.Alignment.LEFT, { site.tooltip },
                )
            }
        }
    }
}

/** Reused for source labels in navigation menus and the screen selector. */
internal class NavigationTextRenderer<T>(private val label: (T) -> String) : SimpleListCellRenderer<T>() {
    init { putClientProperty("html.disable", true) }

    override fun customize(list: JList<out T>, value: T?, index: Int, selected: Boolean, hasFocus: Boolean) {
        text = value?.let(label).orEmpty()
    }
}
