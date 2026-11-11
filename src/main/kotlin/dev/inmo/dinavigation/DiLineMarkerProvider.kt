package dev.inmo.dinavigation

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor
import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.awt.RelativePoint
import com.intellij.xml.util.XmlStringUtil
import org.jetbrains.kotlin.psi.KtFile
import java.awt.event.MouseEvent
import javax.swing.JList

internal object DiIcons {
    val DEFINITION = IconLoader.getIcon("/icons/definition.svg", DiIcons::class.java)
    val LOOKUP = IconLoader.getIcon("/icons/lookup.svg", DiIcons::class.java)
}

class DiLineMarkerProvider : LineMarkerProviderDescriptor() {
    override fun getName() = "Koin DI navigation"
    override fun getIcon() = DiIcons.DEFINITION
    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: MutableList<out PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        val available = elements.toHashSet()
        val files = elements.asSequence().mapNotNull { it.containingFile as? KtFile }.distinct()
        for (file in files) {
            if (DumbService.isDumb(file.project)) continue
            for (site in DiNavigationService.getInstance(file.project).sites(file)) {
                val anchor = site.anchor ?: continue
                if (anchor in available) result.add(marker(anchor, site))
            }
        }
    }

    internal fun marker(anchor: PsiElement, site: DiSite): LineMarkerInfo<PsiElement> {
        val definition = site.call.kind == DiKind.DEFINITION
        val direction = if (definition) "Find Koin usages" else "Find Koin definitions"
        val accessibleName = "$direction: ${site.call.displayType}"
        val tooltip = XmlStringUtil.escapeString(accessibleName)
        return LineMarkerInfo(anchor, anchor.textRange, if (definition) DiIcons.DEFINITION else DiIcons.LOOKUP,
            { tooltip }, GutterIconNavigationHandler { event, _ -> navigate(event, site) },
            GutterIconRenderer.Alignment.LEFT, { accessibleName })
    }

    private fun navigate(event: MouseEvent, site: DiSite) {
        val project = site.pointer.project
        val point = RelativePoint(event)
        val service = DiNavigationService.getInstance(project)
        fun hint(text: String) {
            FileEditorManager.getInstance(project).selectedTextEditor?.let {
                HintManager.getInstance().showInformationHint(it, text)
            }
        }
        if (!service.isReady) hint("Preparing Koin navigation. Progress is shown in the status bar.")
        service.requestTargets(site, onResult = { targets ->
                when (targets.size) {
                    0 -> hint("No matching Koin calls in project sources")
                    1 -> service.open(targets.single())
                    else -> JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
                        .setTitle(if (site.call.kind == DiKind.DEFINITION) "Koin usages" else "Koin definitions")
                        .setRenderer(object : SimpleListCellRenderer<DiSite>() {
                            override fun customize(list: JList<out DiSite>, value: DiSite?, index: Int, selected: Boolean, hasFocus: Boolean) {
                                text = value?.presentation().orEmpty()
                            }
                        })
                        .setItemChosenCallback(service::open)
                        .createPopup().show(point)
                }
            }, onError = ::hint)
    }
}
