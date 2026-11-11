package dev.inmo.idea.navigation.flow.diagram

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.messages.Topic
import dev.inmo.idea.navigation.flow.diagram.analysis.KotlinSource
import dev.inmo.idea.navigation.flow.diagram.analysis.NavigationAnalyzer
import dev.inmo.idea.navigation.flow.diagram.analysis.NavigationGraph
import dev.inmo.idea.navigation.flow.diagram.analysis.SourceLocation
import dev.inmo.idea.navigation.flow.diagram.analysis.SourceSelection
import dev.inmo.idea.navigation.flow.diagram.analysis.TransitionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

internal enum class MarkerKind { ALLOCATIONS, VIEW }

internal data class NavigationTarget(val label: String, val location: SourceLocation)

internal data class NavigationMarker(
    val location: SourceLocation,
    val kind: MarkerKind,
    val tooltip: String,
    val targets: List<NavigationTarget>,
)

internal data class NavigationSnapshot(
    val revision: Long,
    val graph: NavigationGraph,
    val fileStamps: Map<String, Long>,
    val markers: Map<String, List<NavigationMarker>>,
)

internal fun interface NavigationListener {
    fun changed()

    companion object {
        val TOPIC: Topic<NavigationListener> = Topic.create("Navigation analysis updated", NavigationListener::class.java)
    }
}

/** All project traversal happens in a cancellable background read action, never during gutter collection. */
@Service(Service.Level.PROJECT)
internal class NavigationService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val revision = AtomicLong()
    private val notificationPending = AtomicBoolean()
    @Volatile private var completed: NavigationSnapshot? = null
    @Volatile private var contentRoots = listOfNotNull(project.basePath)
    @Volatile var error: String? = null
        private set

    val snapshot: NavigationSnapshot?
        get() = completed?.takeIf { it.revision == revision.get() }

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { requestRefresh() })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = requestRefresh()
        })
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    if (events.any { event ->
                        contentRoots.any { event.path == it || event.path.startsWith("$it/") } &&
                            (event.file?.isDirectory == true || event.path.endsWith(".kt") || event.path.endsWith(".kts"))
                    }) {
                        requestRefresh()
                    }
                }
            })
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            scope.launch {
                for (request in requests) {
                    delay(300)
                    while (requests.tryReceive().isSuccess) { /* Coalesce edits. */ }
                    try {
                        withBackgroundProgress(project, "Analyzing dev.inmo.navigation") {
                            val next = constrainedReadAction(
                                ReadConstraint.inSmartMode(project),
                                ReadConstraint.withDocumentsCommitted(project),
                            ) { analyzeProject() }
                            ensureActive()
                            if (next.revision == revision.get()) completed = next
                        }
                        notifyUpdated()
                    } catch (cancelled: CancellationException) {
                        // A cancelled project scope must terminate the worker immediately.
                        scope.coroutineContext.ensureActive()
                        error = "Navigation analysis was cancelled. Use Refresh to retry."
                        notifyUpdated()
                    } catch (_: ProcessCanceledException) {
                        error = "Navigation analysis was cancelled. Use Refresh to retry."
                        notifyUpdated()
                    } catch (failure: Exception) {
                        logger<NavigationService>().warn("Cannot analyze navigation", failure)
                        error = "Navigation analysis failed. See the IDE log and use Refresh to retry."
                        notifyUpdated()
                    }
                }
            }
        }
        requestRefresh()
    }

    fun requestRefresh() {
        revision.incrementAndGet()
        error = null
        requests.trySend(Unit)
        if (!ApplicationManager.getApplication().isUnitTestMode && notificationPending.compareAndSet(false, true)) {
            scope.launch(Dispatchers.EDT) {
                notificationPending.set(false)
                if (!project.isDisposed) project.messageBus.syncPublisher(NavigationListener.TOPIC).changed()
            }
        }
    }

    private suspend fun notifyUpdated() = withContext(Dispatchers.EDT) {
        if (!project.isDisposed) {
            DaemonCodeAnalyzer.getInstance(project).restart(this@NavigationService)
            project.messageBus.syncPublisher(NavigationListener.TOPIC).changed()
        }
    }

    fun markers(file: KtFile): List<NavigationMarker> {
        val current = snapshot ?: return emptyList()
        val path = file.virtualFile?.path ?: return emptyList()
        if (current.fileStamps[path] != file.modificationStamp) return emptyList()
        return current.markers[path].orEmpty()
    }

    fun open(target: NavigationTarget, expectedRevision: Long) {
        if (snapshot?.revision != expectedRevision) return
        val file = LocalFileSystem.getInstance().findFileByPath(target.location.path) ?: return
        OpenFileDescriptor(project, file, target.location.offset).navigate(true)
    }

    private fun analyzeProject(): NavigationSnapshot {
        val currentRevision = revision.get()
        contentRoots = (ProjectRootManager.getInstance(project).contentRoots.map { it.path } + listOfNotNull(project.basePath)).distinct()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val psiManager = PsiManager.getInstance(project)
        val sources = FileTypeIndex.getFiles(KotlinFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .asSequence()
            .filter { file ->
                val root = project.basePath?.takeIf { file.path.startsWith("$it/") }
                    ?: fileIndex.getContentRootForFile(file)?.path
                root != null && !fileIndex.isInTestSourceContent(file) &&
                    SourceSelection.includes(file.path.removePrefix("$root/"))
            }
            .sortedBy { it.path }
            .mapNotNull { file ->
                ProgressManager.checkCanceled()
                (psiManager.findFile(file) as? KtFile)?.let { KotlinSource(file.path, it) }
            }.toList()
        val graph = NavigationAnalyzer().analyze(sources) { ProgressManager.checkCanceled() }
        return NavigationSnapshot(currentRevision, graph, sources.associate { it.path to it.file.modificationStamp }, buildMarkers(graph))
    }

    @TestOnly
    fun refreshForTests() {
        completed = analyzeProject()
    }

    override fun dispose() { requests.close() }

    companion object {
        fun getInstance(project: Project): NavigationService = project.service()

        internal fun buildMarkers(graph: NavigationGraph): Map<String, List<NavigationMarker>> {
            val markers = mutableListOf<NavigationMarker>()
            fun allocations(name: String, location: SourceLocation, label: String) {
                val targets = graph.allocationsFor(name).map { call ->
                    NavigationTarget("${call.operation} · ${call.sourceConfig?.substringAfterLast('.') ?: "entry / unknown"} · ${call.location.path}:${call.location.line}", call.location)
                }.distinctBy { it.location }.sortedWith(compareBy({ it.location.path }, { it.location.offset }))
                if (targets.isNotEmpty()) markers += NavigationMarker(location, MarkerKind.ALLOCATIONS, "Find navigation allocations of $label (${targets.size})", targets)
            }
            graph.views.forEach { allocations(it.configName, it.declaration, it.viewName) }
            graph.configurations.forEach { allocations(it.name, it.declaration, it.name) }
            graph.transitions.filter { it.targetConfig != null && it.kind != TransitionKind.BACK }.forEach { call ->
                val targets = graph.views.filter { it.configName == call.targetConfig }.map {
                    NavigationTarget("${it.viewName} · ${it.declaration.path}:${it.declaration.line}", it.declaration)
                }.distinctBy { it.location }
                if (targets.isNotEmpty()) markers += NavigationMarker(call.location, MarkerKind.VIEW, "Open View for ${call.targetConfig}", targets)
            }
            // One leaf can represent several statically possible destinations.
            return markers.groupBy { it.location.path }.mapValues { (_, sites) ->
                sites.groupBy { it.location.offset }.values.map { sameLeaf ->
                    val targets = sameLeaf.flatMap { it.targets }.distinctBy { it.location }
                    val first = sameLeaf.first()
                    val tooltip = if (sameLeaf.size == 1) first.tooltip else when (first.kind) {
                        MarkerKind.ALLOCATIONS -> "Find navigation allocations (${targets.size})"
                        MarkerKind.VIEW -> "Open Views for navigation destinations (${targets.size})"
                    }
                    first.copy(targets = targets, tooltip = tooltip)
                }
            }
        }
    }
}

class NavigationStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) { NavigationService.getInstance(project) }
}
