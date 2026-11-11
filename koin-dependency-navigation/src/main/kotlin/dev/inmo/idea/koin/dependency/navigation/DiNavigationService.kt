package dev.inmo.idea.koin.dependency.navigation

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
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.base.analysis.KotlinUastOutOfCodeBlockModificationTracker
import org.jetbrains.kotlin.psi.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Presentation contains no PSI access: Swing can render a result without a read lock. */
internal data class DiSite(
    val pointer: SmartPsiElementPointer<KtExpression>,
    val call: DiCall,
    val offset: Int,
    val filePath: String,
    private val label: String,
) {
    val anchor: PsiElement?
        get() = when (val element = pointer.element) {
            is KtCallExpression -> (element.calleeExpression as? KtNameReferenceExpression)?.getReferencedNameElement()
            is KtNameReferenceExpression -> element.getReferencedNameElement()
            else -> null
        }

    fun presentation(): String = label
}

private data class FileSites(val stamp: Long, val epoch: Long, val dependencies: Map<VirtualFile, Long>, val sites: List<DiSite>)
private data class SemanticStamp(val kotlin: Long, val roots: Long, val names: Set<String>)
private data class RefreshPlan(val revision: Long, val epoch: Long, val names: Set<String>, val files: Set<VirtualFile>)
private data class IndexState(val revision: Long = 0, val ready: Boolean = false, val error: String? = null)
private data class DiSnapshot(val definitions: Map<String, List<DiSite>>, val lookups: Map<String, List<DiSite>>)

@Service(Service.Level.PROJECT)
internal class DiNavigationService(private val project: Project, private val coroutineScope: CoroutineScope) : Disposable {
    private val files = ConcurrentHashMap<VirtualFile, FileSites>()
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow(IndexState())
    private var stamp: SemanticStamp? = null // Only the single refresh worker changes these.
    private var epoch = 0L
    @Volatile private var snapshot = DiSnapshot(emptyMap(), emptyMap())
    private var navigationJob: Job? = null
    private var workerJob: Job? = null
    private val analyzedFiles = AtomicLong()
    internal val analyzedFileCount: Long get() = analyzedFiles.get()
    val isReady: Boolean get() = state.value.ready

    init {
        val connection = project.messageBus.connect(this)
        connection.subscribe(PsiModificationTracker.TOPIC, PsiModificationTracker.Listener { requestRefresh() })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) = requestRefresh()
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun enteredDumbMode() = requestRefresh()
            override fun exitDumbMode() = requestRefresh()
        })
        ApplicationManager.getApplication().messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.file?.isDirectory == true || it.path.substringAfterLast('.') in setOf("kt", "kts", "java") }) {
                    requestRefresh()
                }
            }
        })
        // Fixtures drive the same prepare/file/publish pipeline synchronously for deterministic tests.
        if (!ApplicationManager.getApplication().isUnitTestMode) startWorker()
        requests.trySend(Unit)
    }

    private fun startWorker() {
        if (workerJob?.isActive == true) return
        workerJob = coroutineScope.launch {
            for (request in requests) {
                delay(250)
                while (requests.tryReceive().isSuccess) { /* Coalesce a burst of PSI/VFS changes. */ }
                try {
                    withBackgroundProgress(project, "Indexing Koin DI navigation") {
                        do {
                            val plan = committedRead { prepareRefresh() }
                            for (file in plan.files) {
                                ensureActive()
                                // A write restarts at most one file, not the entire project traversal.
                                committedRead { refreshFile(file, plan) }
                            }
                            val complete = committedRead { publish(plan) }
                        } while (!complete)
                    }
                    withContext(kotlinx.coroutines.Dispatchers.EDT) { DaemonCodeAnalyzer.getInstance(project).restart(this@DiNavigationService) }
                } catch (cancelled: CancellationException) {
                    if (!coroutineScope.isActive) throw cancelled
                    state.update { it.copy(ready = false, error = "Koin indexing was cancelled. Click the marker to retry.") }
                } catch (failure: Exception) {
                    logger<DiNavigationService>().warn("Cannot refresh Koin navigation index", failure)
                    state.update { it.copy(ready = false, error = "Koin indexing failed. See the IDE log; click the marker to retry.") }
                }
            }
        }
    }

    @TestOnly
    fun startWorkerForTests(): Job {
        startWorker()
        requests.trySend(Unit)
        return workerJob!!
    }

    fun requestRefresh() {
        state.update { it.copy(revision = it.revision + 1, ready = false, error = null) }
        requests.trySend(Unit)
    }

    /** Gutter collection reads completed file entries. It performs no project search or K2 analysis. */
    fun sites(file: KtFile): List<DiSite> {
        if (DumbService.isDumb(project)) return emptyList()
        val cached = files[file.virtualFile] ?: return emptyList()
        return cached.sites.takeIf { cached.stamp == file.modificationStamp }.orEmpty()
    }

    /** A warm navigation is a lookup by bean type; no index enumeration or Kotlin resolve. */
    fun targets(origin: DiSite): List<DiSite> {
        check(isReady) { "Koin navigation index is updating" }
        val expression = origin.pointer.element ?: return emptyList()
        val source = files[expression.containingFile.virtualFile]?.sites
            ?.firstOrNull { it.offset == expression.textOffset }?.call ?: return emptyList()
        val table = if (source.kind == DiKind.DEFINITION) snapshot.lookups else snapshot.definitions
        return source.types.asSequence().flatMap { table[it].orEmpty().asSequence() }
            .filter { source.matches(it.call) }
            .distinctBy { it.filePath to it.offset }
            .sortedWith(compareBy({ it.filePath }, { it.offset })).toList()
    }

    fun requestTargets(origin: DiSite, onResult: (List<DiSite>) -> Unit, onError: (String) -> Unit) {
        navigationJob?.cancel()
        if (state.value.error != null) requestRefresh()
        navigationJob = coroutineScope.launch {
            while (true) {
                val ready = state.first { it.ready || it.error != null }
                if (ready.error != null) {
                    withContext(kotlinx.coroutines.Dispatchers.EDT) { onError(ready.error) }
                    return@launch
                }
                val result = committedRead {
                    val current = state.value
                    if (current.ready) current.revision to targets(origin) else null
                } ?: continue
                val delivered = withContext(kotlinx.coroutines.Dispatchers.EDT) {
                    val current = state.value
                    // An edit may run while the completed background query waits in the EDT queue.
                    if (current.ready && current.revision == result.first) {
                        onResult(result.second)
                        true
                    } else false
                }
                if (delivered) return@launch
            }
        }
    }

    /** Revalidate only the chosen smart pointer in a read action, never inside a Swing renderer. */
    fun open(site: DiSite) {
        coroutineScope.launch {
            val descriptor = committedRead {
                val anchor = site.anchor ?: return@committedRead null
                val file = anchor.containingFile.virtualFile ?: return@committedRead null
                OpenFileDescriptor(project, file, anchor.textOffset)
            } ?: return@launch
            withContext(kotlinx.coroutines.Dispatchers.EDT) { descriptor.navigate(true) }
        }
    }

    private suspend fun <T> committedRead(action: () -> T): T = constrainedReadAction(
        ReadConstraint.inSmartMode(project), ReadConstraint.withDocumentsCommitted(project), action = action,
    )

    private fun prepareRefresh(): RefreshPlan {
        val revision = state.value.revision
        val index = com.intellij.util.indexing.FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.projectScope(project)
        val names = DiOperations.names.toMutableSet()
        val pending = ArrayDeque(names)
        while (pending.isNotEmpty()) {
            ProgressManager.checkCanceled()
            index.processValues(DiIndexKeys.NAME, DiIndexKeys.derived(pending.removeFirst()), null, { _, derived ->
                derived.forEach { if (names.add(it)) pending.addLast(it) }
                true
            }, scope)
        }
        val nextStamp = SemanticStamp(
            KotlinUastOutOfCodeBlockModificationTracker.getInstance(project).modificationCount,
            ProjectRootModificationTracker.getInstance(project).modificationCount, names,
        )
        if (stamp != nextStamp) {
            epoch++
            stamp = nextStamp
        }
        val candidates = linkedSetOf<VirtualFile>()
        for (name in names) {
            ProgressManager.checkCanceled()
            candidates.addAll(index.getContainingFiles(DiIndexKeys.NAME, DiIndexKeys.call(name), scope))
        }
        return RefreshPlan(revision, epoch, names, candidates)
    }

    private fun refreshFile(virtualFile: VirtualFile, plan: RefreshPlan) {
        if (!virtualFile.isValid) return
        val manager = PsiManager.getInstance(project)
        val file = manager.findFile(virtualFile) as? KtFile ?: return
        val cached = files[virtualFile]
        if (cached != null && cached.stamp == file.modificationStamp && cached.epoch == plan.epoch &&
            cached.dependencies.all { (dependency, stamp) -> dependency.isValid && manager.findFile(dependency)?.modificationStamp == stamp }) return
        val dependencies = linkedMapOf<VirtualFile, Long>()
        val resolver = DiResolver { element ->
            val source = element.containingFile as? KtFile
            val sourceFile = source?.virtualFile
            if (sourceFile != null && sourceFile != virtualFile && !source.isCompiled) {
                dependencies[sourceFile] = source.modificationStamp
            }
        }
        val result = mutableListOf<DiSite>()
        file.accept(object : KtTreeVisitorVoid() {
            override fun visitCallExpression(expression: KtCallExpression) {
                ProgressManager.checkCanceled()
                if (expression.calleeName() in plan.names) add(expression)
                super.visitCallExpression(expression)
            }
            override fun visitSimpleNameExpression(expression: KtSimpleNameExpression) {
                ProgressManager.checkCanceled()
                if (expression is KtNameReferenceExpression && expression.getReferencedName() in plan.names &&
                    expression.parent !is KtCallExpression && !insideImportOrType(expression)) add(expression)
                super.visitSimpleNameExpression(expression)
            }
            private fun add(expression: KtExpression) {
                val call = resolver.resolve(expression) ?: return
                val line = file.viewProvider.document?.getLineNumber(expression.textOffset)?.plus(1) ?: 1
                val owner = generateSequence(expression.parent) { it.parent }.filterIsInstance<KtNamedDeclaration>().firstOrNull()?.name
                val name = (expression as? KtCallExpression)?.calleeName() ?: (expression as? KtNameReferenceExpression)?.getReferencedName()
                val path = virtualFile.path
                val relative = project.basePath?.let { path.removePrefix("$it/") } ?: path
                val label = "$name · ${call.displayType} — $relative:$line${owner?.let { " ($it)" }.orEmpty()}"
                result.add(DiSite(SmartPointerManager.createPointer(expression), call, expression.textOffset, path, label))
            }
        })
        files[virtualFile] = FileSites(file.modificationStamp, plan.epoch, dependencies, result.toList())
        analyzedFiles.incrementAndGet()
    }

    private fun publish(plan: RefreshPlan): Boolean {
        val expectedState = state.value
        if (expectedState.revision != plan.revision ||
            stamp?.kotlin != KotlinUastOutOfCodeBlockModificationTracker.getInstance(project).modificationCount ||
            stamp?.roots != ProjectRootModificationTracker.getInstance(project).modificationCount) return false
        files.keys.retainAll(plan.files)
        val definitions = linkedMapOf<String, MutableList<DiSite>>()
        val lookups = linkedMapOf<String, MutableList<DiSite>>()
        for (entry in files.values) for (site in entry.sites) {
            val table = if (site.call.kind == DiKind.DEFINITION) definitions else lookups
            for (type in site.call.types) table.getOrPut(type) { mutableListOf() }.add(site)
        }
        snapshot = DiSnapshot(definitions, lookups)
        return state.compareAndSet(expectedState, IndexState(plan.revision, ready = true))
    }

    @TestOnly
    fun refreshForTests(afterFile: () -> Unit = {}) {
        val plan = prepareRefresh()
        plan.files.forEach {
            refreshFile(it, plan)
            afterFile()
        }
        check(publish(plan)) { "PSI changed during fixture refresh" }
    }

    private fun insideImportOrType(expression: PsiElement): Boolean =
        generateSequence(expression.parent) { it.parent }.takeWhile { it !is KtFile }
            .any { it is KtImportDirective || it is KtPackageDirective || it is KtUserType }

    override fun dispose() { requests.close() }

    companion object {
        fun getInstance(project: Project): DiNavigationService = project.service()
    }
}

/** Runs after project opening; smart/committed constraints wait for IDEA's own indexes. */
class DiIndexStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) { DiNavigationService.getInstance(project) }
}
