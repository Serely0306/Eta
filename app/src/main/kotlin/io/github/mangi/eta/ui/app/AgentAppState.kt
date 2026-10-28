package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentContextSnapshot

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePolicy
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentUserAnswer
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.agent.overlay.PendingQuestion
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.CharacterMacros
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.data.repository.CharacterRepository
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.GuiControlStatus
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.runtime.AgentExecutionService
import io.github.mangi.eta.agent.runtime.AgentExternalArchivePayload
import io.github.mangi.eta.agent.runtime.SubagentTranscriptStore
import io.github.mangi.eta.agent.runtime.AgentSubagentTool
import io.github.mangi.eta.agent.runtime.AgentSubagentManager
import io.github.mangi.eta.ui.components.SubagentPanelAction
import io.github.mangi.eta.agent.runtime.AgentInterruptionNote
import io.github.mangi.eta.agent.mcp.server.EtaMcpConversationHistories
import io.github.mangi.eta.agent.runtime.AgentRunArchiveStore
import io.github.mangi.eta.agent.runtime.AgentRunCheckpointStore
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload
import io.github.mangi.eta.agent.runtime.ExternalArchiveIds
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.EtaBackupRepository
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.model.AgentChatHomeUiState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationEntrySource
import io.github.mangi.eta.ui.model.ConversationModeUi
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSubagentUi
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.SubagentDetails
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeCode
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.contentMatches
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class AgentAppState(
    context: Context,
    private val scope: CoroutineScope,
    initialConversations: AgentConversationStore.Snapshot = AgentConversationStore.load(context),
) {
    private val appContext = context.applicationContext
    private val runMessageProjector = AgentRunMessageProjector()
    private val runStore = AgentRunStore(scope) { runId, event -> applyRunEvent(runId, event) }
    private val pendingConversationDeletes = mutableSetOf<String>()
    private val persistenceLock = Any()
    private var persistenceJob: Job? = null
    // 导入期间暂停保存：旧状态的增量保存会删除刚导入的会话；导入成功后整体重载，失败时数据库已回滚。
    private var persistencePaused = false
    private val runtimeRecoveryInProgress = AtomicBoolean(false)
    private val defaultThinkingEnabled = agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
    /**
     * 未发送的新会话最近一次套用的（有效模型, 该模型的默认强度）。换了模型或改了模型的默认强度才重新套用，
     * 用户在新会话里手动选的强度不会被其他刷新覆盖。
     */
    private var newChatEffortSource: Pair<String?, ReasoningEffort?>? = null
    @Volatile
    private var conversationPersistence = AgentConversationPersistence(initialConversations)
    private var currentReasoningCapabilities: ModelReasoningCapabilities? = null
    private var providers: List<ProviderSetting> = emptyList()
    private var defaultModelId: String? = null
    private var defaultProviderId: String? = null
    private var fileAttachmentOwnerVersion = 0L
    private val conversationLoadMutex = Mutex()
    private val conversationContentPins = mutableMapOf<String, Int>()
    private var conversationSelectionJob: Job? = null
    private var conversationSelectionVersion = 0L
    private var historyPageJob: Job? = null
    private var historyPageVersion = 0L
    private var historyPageReleasesEarlier = false
    private var conversationSearchJob: Job? = null
    private var persistedSearchMatches: Set<String> = emptySet()

    var isLoadingConversation by mutableStateOf(false)
        private set
    var conversationLoadFailed by mutableStateOf(false)
        private set
    var historyLoadState by mutableStateOf(ConversationHistoryLoadState.Idle)
        private set
    val isLoadingEarlierMessages: Boolean get() = historyLoadState == ConversationHistoryLoadState.Loading

    private var selectedConversationId: String? = initialConversations.selectedConversationId
    private var conversationsById: Map<String, AgentChatHomeUiState> = initialConversations.conversationsById
    private var conversationTitles: Map<String, String> = initialConversations.titles
    private var conversationUpdatedAt: Map<String, Long> = initialConversations.updatedAt
    // 侧栏的子 Agent 列表按（消息, 记录）引用缓存：摘要随每个流式事件刷新，只有变化的会话重算。
    // 必须在 init 之前声明：init 里刷新摘要时就会用到。
    private val subagentCache = mutableMapOf<String, Triple<List<AgentChatMessageUi>, List<AgentModelClient.ConversationMessage>, List<ConversationSubagentUi>>>()
    // 存储里派出过子 Agent 的会话：未加载的会话只有预览，靠它在侧栏显示入口，展开时再加载。
    private var storedSubagentConversationIds: Set<String> = emptySet()
    // AG-19 起的会话级子 Agent（按会话），侧栏直接从注册表读，不依赖会话是否加载。
    private var registrySubagents: Map<String, List<ConversationSubagentUi>> = emptyMap()
    // 只加载了最近几页的会话，库里全部工具行（按加载窗口的第一条消息记版本，窗口变了就重查），
    // 让侧栏也能列出更早批次的子 Agent。
    private val storedToolRows = mutableMapOf<String, Pair<String?, List<ToolActivityMessageUi>>>()
    private val storedToolRowsLoading = mutableSetOf<String>()

    var homeState by mutableStateOf(
        selectedConversationId?.let(conversationsById::get) ?: emptyChatState(defaultThinkingEnabled)
    )
        private set

    var modelPickerState by mutableStateOf(AgentModelPickerUiState())
        private set

    var conversationPaneState by mutableStateOf(
        ConversationPaneUiState(
            conversations = emptyList(),
            selectedConversationId = selectedConversationId,
            searchQuery = "",
        )
    )
        private set

    init {
        refreshConversationSummaries()
        loadStoredSubagentConversations()
        observeSubagentRegistry()
        observeRuntimeSelection()
        runtimeRecoveryInProgress.set(true)
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    /** 新会话从默认模型的默认强度开始（模型编辑里的“默认强度”），发送前再按模型能力就近取档。 */
    private fun newChatState(): AgentChatHomeUiState {
        val defaultModel = providers.enabledModel(
            AgentModelPickerProjector.project(providers, defaultProviderId, defaultModelId).selectedModel?.id
        )?.model
        val source = defaultModel?.id to defaultModel?.defaultReasoningEffort
        newChatEffortSource = source
        return emptyChatState(defaultThinkingEnabled).let { state ->
            if (defaultThinkingEnabled) state.copy(reasoningEffort = source.second ?: ReasoningEffort.DEFAULT) else state
        }
    }

    private fun observeRuntimeSelection() {
        scope.launch(Dispatchers.IO) {
            combine(
                RuntimeConfigRepository.selectedProviderIdFlow(),
                RuntimeConfigRepository.selectedModelIdFlow(),
                ProviderRepository.providersFlow(),
            ) { providerId, modelId, providers ->
                Triple(providerId, modelId, providers)
            }
                .distinctUntilChanged()
                .collectLatest { (providerId, modelId, providers) ->
                    withContext(Dispatchers.Main) {
                        this@AgentAppState.providers = providers
                        defaultProviderId = providerId
                        defaultModelId = modelId
                        refreshConversationModel()
                    }
                }
        }
    }

    /**
     * 选择器、思考强度与上下文窗口统一投影自当前会话的有效模型：
     * 会话绑定的模型仍可用时用它，否则用默认模型。回落只影响显示与发送，不改写会话绑定，
     * 模型重新启用后会话自动回到原模型；真正发送后才把实际模型写入绑定。
     */
    private fun refreshConversationModel() {
        val bound = providers.enabledModel(homeState.modelId)
        val pickerState = if (bound != null) {
            AgentModelPickerProjector.project(providers, bound.provider.id, bound.model.id)
        } else {
            AgentModelPickerProjector.project(providers, defaultProviderId, defaultModelId)
        }
        modelPickerState = pickerState.copy(isChanging = modelPickerState.isChanging)
        val effective = bound ?: providers.enabledModel(pickerState.selectedModel?.id)
        currentReasoningCapabilities = effective?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
        // 还没发送过的新会话跟随有效模型的默认强度；已有会话保留各自的强度。
        if (selectedConversationId == null && homeState.messages.isEmpty() && defaultThinkingEnabled) {
            val source = effective?.model?.id to effective?.model?.defaultReasoningEffort
            if (source != newChatEffortSource) {
                newChatEffortSource = source
                homeState = homeState.copy(reasoningEffort = source.second ?: ReasoningEffort.DEFAULT)
            }
        }
        if (!homeState.isConversationLoaded) return
        val resolved = homeState.withCurrentReasoningCapabilities()
        val conversationId = selectedConversationId
        if (conversationId == null) homeState = resolved
        else updateConversation(conversationId, resolved, updateTimestamp = false)
    }

    private fun AgentChatHomeUiState.withCurrentReasoningCapabilities(): AgentChatHomeUiState {
        val normalized = currentReasoningCapabilities?.normalize(reasoningEffort) ?: ReasoningEffort.OFF
        return copy(
            thinkingEnabled = normalized.enablesReasoning,
            reasoningEffort = normalized,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            providerDefaultEffort = currentReasoningCapabilities?.defaultEffort,
        )
    }

    private val AgentChatHomeUiState.effectiveModelId: String?
        get() = providers.enabledModel(modelId)?.model?.id
            ?: AgentModelPickerProjector.project(providers, defaultProviderId, defaultModelId).selectedModel?.id

    fun refreshRuntimeResults() {
        if (!runtimeRecoveryInProgress.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                recoverRuntimeRuns()
                importArchivedExternalRuns()
            } finally {
                runtimeRecoveryInProgress.set(false)
            }
        }
    }

    suspend fun exportBackup(output: OutputStream): EtaBackupSummary =
        EtaBackupRepository.export(appContext, output)

    suspend fun importBackup(input: InputStream): EtaBackupSummary {
        val locallyBusy = withContext(Dispatchers.Main.immediate) {
            runStore.hasActiveRuns || conversationsById.values.any { it.isStreaming }
        }
        if (locallyBusy) {
            throw IllegalStateException("请先停止正在运行的 Agent 任务")
        }

        val activeRunQuery = withContext(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).queryActiveRuns()
        }
        when (val active = activeRunQuery) {
            is AgentRuntimeClient.ActiveRunsQuery.Known -> {
                if (active.runIds.isNotEmpty()) {
                    throw IllegalStateException("请先停止正在运行的 Agent 任务")
                }
            }
            AgentRuntimeClient.ActiveRunsQuery.Unavailable -> {
                throw IllegalStateException("无法确认 Agent Runtime 状态，请稍后重试")
            }
        }

        val pendingPersistence = synchronized(persistenceLock) {
            check(!persistencePaused) { "备份正在导入" }
            persistencePaused = true
            persistenceJob
        }
        var imported = false
        var reloaded = false
        try {
            pendingPersistence?.join()
            return conversationLoadMutex.withLock {
                try {
                    val summary = EtaBackupRepository.import(appContext, input, onCommitted = { imported = true })
                    reloadConversationsAfterBackup()
                    reloaded = true
                    summary
                } catch (failure: Throwable) {
                    if (imported && !reloaded) {
                        try {
                            withContext(NonCancellable) { reloadConversationsAfterBackup() }
                            reloaded = true
                        } catch (reloadFailure: Throwable) {
                            failure.addSuppressed(reloadFailure)
                        }
                    }
                    throw failure
                }
            }
        } finally {
            // 导入已提交但重载失败时内存仍是旧状态，继续暂停，避免覆盖导入结果；重启后按库内数据加载。
            if (!imported || reloaded) {
                synchronized(persistenceLock) { persistencePaused = false }
            }
            // 仅提交前失败才回写旧内存；提交后的清理失败或取消仍以导入数据为准。
            if (!imported) withContext(Dispatchers.Main.immediate + NonCancellable) { persistConversations() }
        }
    }

    private suspend fun reloadConversationsAfterBackup() {
        val snapshot = withContext(Dispatchers.IO) {
            AgentConversationStore.load(appContext)
        }
        withContext(Dispatchers.Main.immediate) {
            conversationSelectionVersion += 1
            cancelHistoryPageLoad()
            conversationSelectionJob?.cancel()
            isLoadingConversation = false
            conversationLoadFailed = false
            contentMatchCache.clear()
            conversationSearchJob?.cancel()
            persistedSearchMatches = emptySet()
            selectedConversationId = snapshot.selectedConversationId
            conversationsById = snapshot.conversationsById
            conversationTitles = snapshot.titles
            conversationUpdatedAt = snapshot.updatedAt
            conversationPersistence = AgentConversationPersistence(snapshot)
            fileAttachmentOwnerVersion += 1
            homeState = selectedConversationId
                ?.let(conversationsById::get)
                ?: newChatState()
            refreshConversationModel()
            conversationPaneState = conversationPaneState.copy(
                selectedConversationId = selectedConversationId,
                searchQuery = "",
            )
            refreshConversationSummaries()
        }
    }

    /** 用 checkpoint、终态 outbox 与 active session 一次性对账，避免用进程存活推断 run 状态。 */
    private suspend fun recoverRuntimeRuns() {
        val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
        val checkpoints = withContext(Dispatchers.IO) {
            AgentRunCheckpointStore.list(appContext)
        }
        val initialCompletedQuery = client.queryCompletedRuns()
        if (initialCompletedQuery is AgentRuntimeClient.CompletedRunsQuery.Unavailable) {
            AndroidAgentLogger.warnThrottled("agent_ui_drain_results_failed") {
                "Agent UI pending result recovery failed"
            }
        }
        val initialCompletedRuns =
            (initialCompletedQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val activeRunQuery = client.queryActiveRuns()
        val terminalRaceQuery = if (
            activeRunQuery is AgentRuntimeClient.ActiveRunsQuery.Known
        ) {
            client.queryCompletedRuns()
        } else {
            initialCompletedQuery
        }
        val terminalRaceCompletedRuns =
            (terminalRaceQuery as? AgentRuntimeClient.CompletedRunsQuery.Known)
                ?.runs
                .orEmpty()
        val completedRuns = (initialCompletedRuns + terminalRaceCompletedRuns)
            .associateBy { completed ->
                completed.result.runId.ifBlank { completed.handoff.id }
            }
            .values
            .toList()
        val activeStateKnown = activeRunQuery is AgentRuntimeClient.ActiveRunsQuery.Known
        val terminalStateKnown = terminalRaceQuery is AgentRuntimeClient.CompletedRunsQuery.Known
        val activeRunIds = (activeRunQuery as? AgentRuntimeClient.ActiveRunsQuery.Known)?.runIds.orEmpty()
        val locallyObservedRunIds = withContext(Dispatchers.Main) { runStore.observedRunIds }
        val plan = AgentRunRecoveryCoordinator.plan(
            checkpoints = checkpoints,
            completedRuns = completedRuns,
            activeStateKnown = activeStateKnown,
            terminalStateKnown = terminalStateKnown,
            activeRunIds = activeRunIds,
            locallyObservedRunIds = locallyObservedRunIds,
        )
        val knownRunIds = checkpoints.mapTo(mutableSetOf()) { it.runId }.apply {
            addAll(completedRuns.map { it.result.runId.ifBlank { it.handoff.id } })
            addAll(locallyObservedRunIds)
        }
        val preparingRuns = (activeRunQuery as? AgentRuntimeClient.ActiveRunsQuery.Known)?.runs.orEmpty()
            .filter { it.handoffSource == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE && it.runId !in knownRunIds }
        val recoveringConversationIds = buildSet {
            plan.completed.forEach { add(AgentUiHandoffPayload.from(it.result.handoff.payload).conversationId) }
            plan.interrupted.forEach { add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId) }
            plan.reattach.forEach { add(AgentUiHandoffPayload.from(it.handoff.payload).conversationId) }
            preparingRuns.forEach { add(it.conversationId) }
        }
        withLoadedConversations(recoveringConversationIds) {
            val orphanRewrites = if (activeStateKnown && terminalStateKnown) withContext(Dispatchers.Main) {
                val observed = checkpoints.mapTo(mutableSetOf()) { it.runId }.apply {
                    addAll(completedRuns.map { it.result.runId })
                    addAll(activeRunIds)
                    addAll(runStore.observedRunIds)
                }
                conversationsById.flatMap { (id, state) ->
                    state.roleplayMessages.pendingRewrites.keys.filterNot { it in observed }.map { id to it }
                }
            } else emptyList()
            if (
                plan.completed.isEmpty() &&
                plan.interrupted.isEmpty() &&
                plan.reattach.isEmpty() && preparingRuns.isEmpty() && orphanRewrites.isEmpty()
            ) {
                return@withLoadedConversations
            }

            val acknowledgeAfterSave = mutableListOf<String>()
            val removeAfterSave = mutableListOf<String>()
            // 查系统的进程退出记录要走 binder，放在主线程之外。
            val interruptionCauses = plan.interrupted.associate { checkpoint ->
                checkpoint.runId to AgentInterruptionNote.cause(appContext, checkpoint.updatedAt)
            }
            val changed = withContext(Dispatchers.Main) {
                var stateChanged = false
                plan.completed.forEach { recoveryPlan ->
                    val completedRun = recoveryPlan.result
                    val runId = completedRun.result.runId.ifBlank { completedRun.handoff.id }
                    val payload = AgentUiHandoffPayload.from(completedRun.handoff.payload)
                    val conversationId = payload.conversationId
                    val state = conversationsById[conversationId]
                    if (state == null) {
                        // App 请求先保存会话才启动；缺失会话的结果已被用户删除或备份替换，不能重新建会话。
                        acknowledgeAfterSave += runId
                        return@forEach
                    }
                    recoveryPlan.checkpoint?.let { checkpoint ->
                        stateChanged = restoreCheckpointTrace(
                            checkpoint = checkpoint,
                            interrupted = false,
                        ) || stateChanged
                    }
                    val result = completedRun.result
                    val recovery = AgentPendingResultRecovery.apply(
                        state = conversationsById[conversationId] ?: state,
                        runId = runId,
                        result = result,
                        promptSupplement = payload.promptSupplement,
                        supplements = payload.supplements,
                    )
                    if (recovery.alreadyApplied) {
                        acknowledgeAfterSave += runId
                        return@forEach
                    }
                    updateConversation(conversationId, recovery.state)
                    acknowledgeAfterSave += runId
                    stateChanged = true
                }

                plan.interrupted.forEach { checkpoint ->
                    removeAfterSave += checkpoint.runId
                    stateChanged = restoreCheckpointTrace(
                        checkpoint = checkpoint,
                        interrupted = true,
                        interruptionCause = interruptionCauses[checkpoint.runId],
                    ) || stateChanged
                }
                orphanRewrites.forEach { (conversationId, runId) ->
                    conversationsById[conversationId]?.let { state ->
                        updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(state, runId,
                            AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                                operation = AgentRuntimeWire.OP_REWRITE_REPLY)))
                        stateChanged = true
                    }
                }
                if (stateChanged) refreshConversationSummaries()
                stateChanged || acknowledgeAfterSave.isNotEmpty() || removeAfterSave.isNotEmpty()
            }

            if (changed) {
                val saved = withContext(Dispatchers.Main) { persistConversations() }.await()
                if (saved) {
                    val acknowledged = acknowledgeAfterSave.filterTo(mutableSetOf(), client::ackResult)
                    removeAfterSave.forEach { runId ->
                        AgentRunCheckpointStore.remove(appContext, runId)
                    }
                    withContext(Dispatchers.Main) {
                        plan.completed.forEach { completed ->
                            val runId = completed.result.result.runId.ifBlank { completed.result.handoff.id }
                            val id = AgentUiHandoffPayload.from(completed.result.handoff.payload).conversationId
                            if (id in pendingConversationDeletes && runStore.activeRunId(id) == null) {
                                if (runId in acknowledged) deleteSettledConversation(id)
                                else abortPendingConversationDelete(id)
                            }
                        }
                        plan.interrupted.forEach { checkpoint ->
                            val id = AgentUiHandoffPayload.from(checkpoint.handoff.payload).conversationId
                            if (id in pendingConversationDeletes && runStore.activeRunId(id) == null) {
                                deleteSettledConversation(id)
                            }
                        }
                    }
                }
            }

            plan.reattach.forEach { checkpoint ->
                withContext(Dispatchers.Main) {
                    startReattachedRun(checkpoint.runId,
                        AgentUiHandoffPayload.from(checkpoint.handoff.payload).conversationId,
                        checkpoint.operation, checkpoint.rewriteTargetMessageId)
                }
            }
            preparingRuns.forEach { run ->
                withContext(Dispatchers.Main) { startReattachedRun(run.runId, run.conversationId, run.operation) }
            }
        }
    }

    /** 把安全事件恢复为 UI 轨迹；半截回复不进入模型 history，设备工具也不会重放。 */
    private fun restoreCheckpointTrace(
        checkpoint: AgentRunCheckpointStore.Checkpoint,
        interrupted: Boolean,
        interruptionCause: String? = null,
    ): Boolean {
        val runId = checkpoint.runId
        if (runId.isBlank()) return false
        val conversationId = AgentUiHandoffPayload
            .from(checkpoint.handoff.payload)
            .conversationId
        val existing = conversationsById[conversationId] ?: return false
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId)) return false
        if (checkpoint.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            val restored = RoleplayConversationReducer.restorePendingRewrite(existing, runId, checkpoint.rewriteTargetMessageId)
            if (interrupted) updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(
                restored, runId, AgentRuntimeWire.RunResult(runId, false, "", "重新生成已中断，原回复已保留。",
                    operation = AgentRuntimeWire.OP_REWRITE_REPLY, rewriteTargetMessageId = checkpoint.rewriteTargetMessageId),
            )) else updateConversation(conversationId, restored)
            return interrupted || restored != existing
        }

        if (!runStore.register(runId, conversationId, observed = false)) return false
        updateConversation(conversationId, existing.copy(isStreaming = true, isCompacting = checkpoint.operation == AgentRuntimeWire.OP_COMPACT))
        restoreRunEvents(runId, checkpoint.events)
        flushPendingRunDelta(runId)
        updateRunTrace(runId) { messages ->
            val finalizedThinking = runMessageProjector.finalizeThinking(runId, messages)
            val finalizedText = runMessageProjector.finalizeText(runId, finalizedThinking)
            if (interrupted) {
                val interruptedTools = runMessageProjector.interruptRunningTools(
                    reason = appContext.getString(R.string.system_notice_interrupted),
                    messages = runMessageProjector.finishContextCompaction(runId, finalizedText, "上下文压缩已中断"),
                )
                val noticeId = "interrupted-$runId"
                if (interruptedTools.any { it.id == noticeId }) {
                    interruptedTools
                } else {
                    interruptedTools + SystemNoticeMessageUi(
                        id = noticeId,
                        code = SystemNoticeCode.Interrupted,
                    )
                }
            } else {
                runMessageProjector.finalizeRun(runId, finalizedText)
            }
        }
        if (interrupted && (checkpoint.contextSnapshot != null || checkpoint.transcript.isNotEmpty())) {
            applyConversationHistoryResult(runId, checkpoint.transcript, checkpoint.contextSnapshot, true)
        }
        if (interrupted && checkpoint.operation == AgentRuntimeWire.OP_CHAT) {
            conversationsById[conversationId]?.let { state ->
                val noteId = AgentInterruptionNote.messageId(runId)
                if (state.history.none { it.messageId == noteId }) {
                    val note = AgentInterruptionNote.build(
                        runId = runId,
                        interruptedAt = checkpoint.updatedAt,
                        cause = interruptionCause ?: "Eta 进程结束",
                        transcript = checkpoint.transcript,
                        events = checkpoint.events,
                    )
                    updateConversation(conversationId, state.copy(history = state.history + note))
                }
            }
        }
        conversationsById[conversationId]?.let { state ->
            updateConversation(conversationId, RoleplayConversationReducer.linkRun(state, runId))
        }
        setConversationStreaming(runId, false)
        runMessageProjector.clearRun(runId)
        runStore.remove(runId)
        conversationUpdatedAt = conversationUpdatedAt +
            (conversationId to checkpoint.updatedAt)
        return true
    }

    private fun startReattachedRun(
        runId: String,
        conversationId: String,
        operation: String,
        rewriteTargetMessageId: String? = null,
    ) {
        val existing = conversationsById[conversationId] ?: return
        if (AgentRuntimeHistoryReducer.wasApplied(existing, runId) ||
            !runStore.register(runId, conversationId)) return
        val restored = if (operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
            RoleplayConversationReducer.restorePendingRewrite(existing, runId, rewriteTargetMessageId)
        } else existing
        updateConversation(conversationId, restored.copy(isStreaming = true, isCompacting = operation == AgentRuntimeWire.OP_COMPACT))
        refreshConversationSummaries()
        val observationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val client = AgentRuntimeClient(appContext, AndroidAgentLogger)
            val outcome = client.attachRun(
                runId = runId,
                onReplay = { events -> restoreRunEvents(runId, events) },
                onEvent = { event -> enqueueRunEvent(runId, event) },
            )
            when (outcome) {
                is AgentRuntimeClient.AttachOutcome.Completed -> withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId = runId,
                        result = outcome.result,
                        acknowledgeRuntimeResult = true,
                    )
                }
                AgentRuntimeClient.AttachOutcome.NotActive -> {
                    withContext(Dispatchers.Main) {
                        setConversationStreaming(runId, false)
                        runStore.remove(runId)
                    }
                    recoverRuntimeRuns()
                    withContext(Dispatchers.Main) {
                        if (runStore.activeRunId(conversationId) == null) abortPendingConversationDelete(conversationId)
                    }
                }
                AgentRuntimeClient.AttachOutcome.Unavailable -> withContext(Dispatchers.Main) {
                    setConversationStreaming(runId, false)
                    runStore.remove(runId)
                    abortPendingConversationDelete(conversationId)
                    refreshConversationSummaries()
                }
            }
        }
        runStore.observe(runId, observationJob)
        observationJob.start()
    }

    private suspend fun importArchivedExternalRuns() {
        val archivedRuns = withContext(Dispatchers.IO) {
            AgentRunArchiveStore.list(appContext)
                .filter { AgentExternalArchivePayload.from(it.handoff.payload) != null }
        }
        if (archivedRuns.isEmpty()) return

        for (archivedRun in archivedRuns) {
            val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: continue
            // 必须加载实际写入的会话：旧编号的会话存在时写入它，未加载就会在占位状态上追加。
            val conversationId = withContext(Dispatchers.Main.immediate) {
                externalArchiveTargetId(archivedRun.handoff.source, payload.conversationKey)
            }
            val saved = withLoadedConversations(setOf(conversationId)) {
                val importedRunId = withContext(Dispatchers.Main) {
                    importExternalRun(archivedRun).also { refreshConversationSummaries() }
                } ?: return@withLoadedConversations true
                if (!withContext(Dispatchers.Main) { persistConversations() }.await()) return@withLoadedConversations false
                AgentRunArchiveStore.remove(appContext, importedRunId)
                true
            }
            if (!saved) break
        }
    }

    suspend fun openAssistantConversation(conversationKey: String): Boolean =
        openArchivedConversation(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE, conversationKey)

    /** 打开外部入口按 [source] 与 [conversationKey] 归档的会话；先导入尚未入库的归档结果。 */
    suspend fun openArchivedConversation(source: String, conversationKey: String): Boolean {
        if (source.isBlank() || conversationKey.isBlank()) return false
        importArchivedExternalRuns()
        return withContext(Dispatchers.Main.immediate) {
            val conversationId = archiveConversationId(
                source = source,
                conversationKey = conversationKey,
            )
            if (conversationsById[conversationId] == null) {
                false
            } else {
                selectConversation(conversationId)
                true
            }
        }
    }

    /** 旧版本按不带来源的编号归档；同一厂商会话已存在时继续写入原会话。 */
    private fun externalArchiveTargetId(source: String, conversationKey: String): String =
        legacyArchiveConversationId(source, conversationKey).takeIf { it in conversationsById }
            ?: archiveConversationId(source = source, conversationKey = conversationKey)

    private fun importExternalRun(archivedRun: AgentRunArchiveStore.ArchivedRun): String? {
        val runId = archivedRun.result.runId.ifBlank { archivedRun.handoff.id }
        if (runId.isBlank()) return null
        val payload = AgentExternalArchivePayload.from(archivedRun.handoff.payload) ?: return null
        val conversationId = externalArchiveTargetId(archivedRun.handoff.source, payload.conversationKey)
        val archivedEffort = payload.reasoningEffort
            ?: payload.thinkingEnabled?.let(ReasoningEffort::fromLegacy)
            ?: ReasoningEffort.fromLegacy(defaultThinkingEnabled)
        val existingState = conversationsById[conversationId] ?: emptyChatState(
            archivedEffort.enablesReasoning
        ).copy(reasoningEffort = archivedEffort)
        val alreadyImported = AgentRuntimeHistoryReducer.wasApplied(existingState, runId) ||
            existingState.messages.any {
                it is AgentMessageUi &&
                    (it.id == "assistant-$runId" || it.id.startsWith("assistant-$runId-")) &&
                    !it.isStreaming
            }
        if (alreadyImported) return runId

        if (conversationTitles[conversationId].isNullOrBlank()) {
            conversationTitles = conversationTitles + (conversationId to payload.title)
        }
        if (runStore.activeRunId(conversationId) != null ||
            !runStore.register(runId, conversationId, observed = false)) return null
        updateConversation(
            conversationId,
            existingState.copy(
                input = "",
                isStreaming = true,
                thinkingEnabled = archivedEffort.enablesReasoning,
                reasoningEffort = archivedEffort,
                pendingImages = emptyList(),
                messages = existingState.messages +
                    UserMessageUi(
                        id = "user-$runId",
                        content = payload.userText,
                        images = archivedRun.userImagePreviews,
                    ) +
                    AgentMessageUi(
                        id = "assistant-$runId",
                        content = "",
                        isStreaming = true,
                        renderMarkdown = false,
                    ),
            )
        )
        archivedRun.events.forEach { event -> applyRunEvent(runId, event) }
        applyRunResult(runId, archivedRun.result)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to archivedRun.createdAt)
        return runId
    }

    fun updateThinkingEnabled(enabled: Boolean) {
        updateReasoningEffort(ReasoningEffort.fromLegacy(enabled))
    }

    fun updateReasoningEffort(effort: ReasoningEffort) {
        if (!homeState.isConversationLoaded) return
        val normalized = currentReasoningCapabilities?.normalize(effort) ?: ReasoningEffort.OFF
        updateCurrentConversation(
            homeState.copy(
                thinkingEnabled = normalized.enablesReasoning,
                reasoningEffort = normalized,
            )
        )
        if (selectedConversationId != null) persistConversations()
    }

    /** 聊天内切换同时改当前会话绑定与默认模型；默认模型供新会话和系统助手入口使用。 */
    fun selectModel(modelId: String) {
        if (
            !homeState.isConversationLoaded ||
            homeState.isStreaming ||
            modelPickerState.isChanging ||
            modelPickerState.selectedModel?.id == modelId
        ) {
            return
        }
        updateCurrentConversation(homeState.copy(modelId = modelId))
        refreshConversationModel()
        if (selectedConversationId != null) persistConversations()
        modelPickerState = modelPickerState.copy(isChanging = true)
        scope.launch(Dispatchers.IO) {
            try {
                RuntimeConfigRepository.setSelectedModelId(modelId)
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_model_switching_failed_please_try_again_later_4af439), Toast.LENGTH_SHORT).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    modelPickerState = modelPickerState.copy(isChanging = false)
                }
            }
        }
    }

    fun updateSearchQuery(query: String) {
        conversationPaneState = conversationPaneState.copy(searchQuery = query)
        conversationSearchJob?.cancel()
        persistedSearchMatches = emptySet()
        contentMatchCache.clear()
        refreshConversationSummaries()
        if (query.isBlank()) return
        conversationSearchJob = scope.launch {
            delay(200)
            try {
                persistedSearchMatches = AgentConversationStore.searchConversationIds(
                    appContext, query.trim(),
                    SystemNoticeCode.entries.filter { noticeText(it).contains(query.trim(), ignoreCase = true) },
                )
                refreshConversationSummaries()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Agent conversation search failed: type=${failure.safeLogType()}")
            }
        }
    }

    fun selectConversation(conversationId: String) {
        if (homeState.messageEdit != null) cancelMessageEdit()
        val state = conversationsById[conversationId] ?: return
        val selectionVersion = ++conversationSelectionVersion
        cancelHistoryPageLoad()
        conversationSelectionJob?.cancel()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = conversationId
        homeState = state
        conversationLoadFailed = false
        isLoadingConversation = !state.isConversationLoaded
        conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        conversationSelectionJob = scope.launch {
            try {
                if (!ensureConversationLoaded(conversationId)) {
                    if (conversationSelectionVersion == selectionVersion) conversationLoadFailed = true
                    return@launch
                }
                if (conversationSelectionVersion != selectionVersion) return@launch
                refreshConversationModel()
                if (homeState.roleplayMessages.pendingRewrites.isNotEmpty()) refreshRuntimeResults()
                if (homeState.modelId != null && providers.enabledModel(homeState.modelId) == null) {
                    modelPickerState.selectedModel?.let { fallback ->
                        Toast.makeText(appContext, appContext.getString(
                            R.string.conversation_model_unavailable, fallback.displayName,
                        ), Toast.LENGTH_SHORT).show()
                    }
                }
                persistConversations()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.error("Agent conversation load failed: type=${failure.safeLogType()}")
                if (conversationSelectionVersion == selectionVersion) conversationLoadFailed = true
            } finally {
                if (conversationSelectionVersion == selectionVersion) isLoadingConversation = false
            }
        }
    }

    fun retryConversationLoad() {
        selectedConversationId?.let(::selectConversation)
    }

    private suspend fun ensureConversationLoaded(conversationId: String): Boolean =
        withContext(Dispatchers.Main.immediate) {
            conversationLoadMutex.withLock {
                val state = conversationsById[conversationId] ?: return@withLock false
                if (state.isConversationLoaded) return@withLock true
                synchronized(persistenceLock) { persistenceJob }?.join()
                val loaded = AgentConversationStore.loadConversation(appContext, conversationId)
                    ?: return@withLock false
                val current = conversationsById[conversationId] ?: return@withLock false
                val restored = loaded.copy(
                    input = current.input,
                    pendingImages = current.pendingImages,
                    pendingFileReferences = current.pendingFileReferences,
                )
                conversationPersistence.adoptConversation(
                    conversationId, restored, conversationTitles[conversationId].orEmpty(),
                    conversationUpdatedAt[conversationId] ?: 0L,
                )
                val latest = conversationsById[conversationId] ?: return@withLock false
                updateConversation(conversationId, restored.copy(
                    input = latest.input,
                    pendingImages = latest.pendingImages,
                    pendingFileReferences = latest.pendingFileReferences,
                ), updateTimestamp = false)
                refreshConversationSummaries()
                true
            }
        }

    private suspend fun <T> withLoadedConversations(ids: Set<String>, block: suspend () -> T): T {
        withContext(Dispatchers.Main.immediate) {
            ids.forEach { conversationContentPins[it] = (conversationContentPins[it] ?: 0) + 1 }
        }
        try {
            ids.forEach { id ->
                val loaded = ensureConversationLoaded(id)
                check(loaded || withContext(Dispatchers.Main.immediate) { id !in conversationsById }) {
                    "Conversation content unavailable"
                }
            }
            return block()
        } finally {
            withContext(Dispatchers.Main.immediate + NonCancellable) {
                ids.forEach { id ->
                    val count = (conversationContentPins[id] ?: 1) - 1
                    if (count == 0) conversationContentPins.remove(id) else conversationContentPins[id] = count
                }
                if (ids.isNotEmpty()) persistConversations()
            }
        }
    }

    fun loadEarlierMessages() = changeMessageWindow(loadEarlier = true)

    fun updateHistoryViewport(atLatest: Boolean) {
        if (!atLatest) {
            if (historyPageReleasesEarlier) cancelHistoryPageLoad()
        } else if (AgentConversationStore.shouldReleaseEarlierMessages(homeState.messages.size)) {
            changeMessageWindow(loadEarlier = false)
        }
    }

    private fun changeMessageWindow(loadEarlier: Boolean) {
        val conversationId = selectedConversationId ?: return
        if (historyPageJob?.isActive == true || !homeState.isConversationLoaded ||
            (loadEarlier && !homeState.hasEarlierMessages) || homeState.messageEdit != null) return
        if (homeState.isStreaming) {
            // 运行中只往前插入更早的一页（AG-29）；释放旧页等运行结束后再做。
            if (loadEarlier) prependEarlierPage(conversationId)
            return
        }
        val pageVersion = ++historyPageVersion
        historyPageReleasesEarlier = !loadEarlier
        historyLoadState = ConversationHistoryLoadState.Loading
        historyPageJob = scope.launch {
            try {
                conversationLoadMutex.withLock {
                    // 翻页或释放旧页都先提交当前窗口，避免覆盖编辑结果或丢弃未保存的记录。
                    val state = conversationsById[conversationId] ?: return@withLock
                    val title = conversationTitles[conversationId].orEmpty()
                    val updatedAt = conversationUpdatedAt[conversationId] ?: 0L
                    check(persistConversations().await()) { "Conversation persistence failed" }
                    val page = if (loadEarlier) {
                        AgentConversationStore.loadEarlierMessages(appContext, conversationId, state.messageStartIndex)
                    } else {
                        AgentConversationStore.loadRecentMessages(appContext, conversationId)
                    }
                    val current = conversationsById[conversationId] ?: return@withLock
                    if (!current.canReplaceMessageWindowOf(state)) return@withLock
                    var window = RoleplayConversationReducer.decorate(state.copy(
                        messages = if (loadEarlier) page.messages + state.messages else page.messages,
                        messageStartIndex = page.startIndex,
                        hasEarlierMessages = page.hasEarlierMessages,
                    ))
                    window = window.copy(roleplayOriginalContents = AgentConversationStore.loadRoleplayMessageContents(
                        appContext, conversationId, RoleplayConversationReducer.requiredOriginalContentIds(window),
                    ))
                    conversationPersistence.adoptConversation(
                        conversationId, window, title, updatedAt,
                    )
                    val latest = conversationsById[conversationId] ?: return@withLock
                    if (!latest.canReplaceMessageWindowOf(state)) return@withLock
                    updateConversation(conversationId, latest.copy(
                        messages = window.messages,
                        messageStartIndex = window.messageStartIndex,
                        hasEarlierMessages = window.hasEarlierMessages,
                        roleplayOriginalContents = window.roleplayOriginalContents,
                    ), updateTimestamp = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.error("Agent message page load failed: type=${failure.safeLogType()}")
                if (historyPageVersion == pageVersion && loadEarlier) {
                    historyLoadState = ConversationHistoryLoadState.Failed
                }
            } finally {
                if (historyPageVersion == pageVersion) {
                    historyPageReleasesEarlier = false
                    if (historyLoadState == ConversationHistoryLoadState.Loading) {
                        historyLoadState = ConversationHistoryLoadState.Idle
                    }
                }
            }
        }
    }

    /**
     * 运行中加载更早的一页（AG-29）：消息仍在不断变化，不能像空闲时那样先保存再整窗替换；
     * 读出的页直接插到当前最新状态的前面，保存基线同步前移。期间窗口起点变了就放弃这一页。
     */
    private fun prependEarlierPage(conversationId: String) {
        val pageVersion = ++historyPageVersion
        historyPageReleasesEarlier = false
        historyLoadState = ConversationHistoryLoadState.Loading
        historyPageJob = scope.launch {
            try {
                conversationLoadMutex.withLock {
                    val startIndex = conversationsById[conversationId]?.messageStartIndex ?: return@withLock
                    val page = AgentConversationStore.loadEarlierMessages(appContext, conversationId, startIndex)
                    val latest = conversationsById[conversationId]?.takeIf { it.canPrependEarlierPage(startIndex) }
                        ?: return@withLock
                    val originals = AgentConversationStore.loadRoleplayMessageContents(
                        appContext, conversationId,
                        RoleplayConversationReducer.requiredOriginalContentIds(latest.withEarlierPage(page)),
                    )
                    // 读原文期间又有新的流式内容，以最新状态为准再插入一次。
                    val current = conversationsById[conversationId]?.takeIf { it.canPrependEarlierPage(startIndex) }
                        ?: return@withLock
                    conversationPersistence.prependEarlierMessages(conversationId, page.messages, startIndex, page.startIndex)
                    updateConversation(conversationId, current.withEarlierPage(page).copy(
                        roleplayOriginalContents = current.roleplayOriginalContents + originals,
                    ), updateTimestamp = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.error("Agent message page prepend failed: type=${failure.safeLogType()}")
                if (historyPageVersion == pageVersion) historyLoadState = ConversationHistoryLoadState.Failed
            } finally {
                if (historyPageVersion == pageVersion && historyLoadState == ConversationHistoryLoadState.Loading) {
                    historyLoadState = ConversationHistoryLoadState.Idle
                }
            }
        }
    }

    private fun cancelHistoryPageLoad() {
        historyPageVersion += 1
        historyPageReleasesEarlier = false
        historyPageJob?.cancel()
        historyPageJob = null
        historyLoadState = ConversationHistoryLoadState.Idle
    }

    private suspend fun releaseInactiveConversations(saved: Map<String, AgentChatHomeUiState>) {
        conversationLoadMutex.withLock {
            for ((id, state) in saved) {
                // 较旧的排队保存可能在卸载后重新持有该会话基线，必须再次释放。
                if (conversationsById[id]?.isConversationLoaded != true) {
                    conversationPersistence.forgetConversation(id)
                    continue
                }
                if (!state.isConversationLoaded || id == selectedConversationId || id in conversationContentPins ||
                    state.isStreaming || runStore.activeRunId(id) != null ||
                    state.messageEdit != null || conversationsById[id] !== state) continue
                conversationPersistence.forgetConversation(id)
                if (id == selectedConversationId || id in conversationContentPins ||
                    runStore.activeRunId(id) != null || conversationsById[id] !== state) continue
                conversationsById = conversationsById + (id to AgentConversationStore.unloadConversation(state))
                contentMatchCache.remove(id)
            }
        }
    }

    fun createConversation() {
        conversationSelectionVersion += 1
        cancelHistoryPageLoad()
        conversationSelectionJob?.cancel()
        isLoadingConversation = false
        conversationLoadFailed = false
        if (homeState.messageEdit != null) cancelMessageEdit()
        fileAttachmentOwnerVersion += 1
        selectedConversationId = null
        homeState = newChatState()
        refreshConversationModel()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = null,
            searchQuery = "",
        )
        refreshConversationSummaries()
        persistConversations()
    }

    fun startCharacterConversation(binding: RoleplayBinding, greeting: String) {
        createConversation()
        val id = newConversationId()
        val greetingId = "greeting-$id"
        val text = CharacterMacros.expand(
            text = greeting, card = CharacterCardCodec.decodeJson(binding.cardSnapshotJson),
            userName = binding.userName, userDescription = binding.userDescription,
        )
        val transcript = if (text.isBlank()) emptyList() else listOf(
            AgentModelClient.ConversationMessage(role = "assistant", content = text, messageId = greetingId),
        )
        selectedConversationId = id
        homeState = newChatState().withCurrentReasoningCapabilities().copy(
            roleplay = binding,
            history = transcript,
            journal = transcript,
            messages = if (text.isBlank()) emptyList() else listOf(AgentMessageUi(greetingId, text, characterEditable = true)),
            roleplayMessages = RoleplayMessageState(links = if (text.isBlank()) emptyMap() else mapOf(
                greetingId to RoleplayMessageLink(greetingId),
            )),
        )
        conversationTitles = conversationTitles + (id to binding.characterName)
        updateConversation(id, homeState)
        conversationPaneState = conversationPaneState.copy(selectedConversationId = id, searchQuery = "")
        refreshConversationSummaries()
        persistConversations()
    }

    fun selectReplyCandidate(messageId: String, index: Int) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val updated = RoleplayConversationReducer.select(homeState, messageId, index) ?: return
        updateCurrentConversation(updated)
        refreshConversationSummaries()
        persistConversations()
    }

    fun deleteConversation(conversationId: String) {
        val runId = runStore.activeRunId(conversationId)
        if (runId != null) {
            pendingConversationDeletes.add(conversationId)
            stopRun(runId)
            return
        }
        deleteSettledConversation(conversationId)
    }

    private fun deleteSettledConversation(conversationId: String) {
        pendingConversationDeletes.remove(conversationId)
        BrowserSessionRegistry.requestRemove(conversationId)
        val wasSelected = selectedConversationId == conversationId
        // 子 Agent 的过程文件按 run 保存，随会话一并删除。
        conversationsById[conversationId]?.appliedRuntimeRunIds?.takeIf { it.isNotEmpty() }?.let { runIds ->
            scope.launch(Dispatchers.IO) { SubagentTranscriptStore.deleteRuns(appContext, runIds) }
        }
        // 电脑端会话的模型历史另存一份，随会话删除。
        if (conversationId.startsWith(ExternalArchiveIds.EXTERNAL_PREFIX)) {
            scope.launch(Dispatchers.IO) { EtaMcpConversationHistories.deleteForConversation(appContext, conversationId) }
        }
        // 会话里的子 Agent（AG-19）与它们的通知随会话删除，运行中的先停。
        scope.launch(Dispatchers.IO) {
            runCatching { AgentSubagentManager.get(appContext).deleteConversation(conversationId) }
        }
        conversationsById = conversationsById - conversationId
        conversationTitles = conversationTitles - conversationId
        conversationUpdatedAt = conversationUpdatedAt - conversationId
        if (wasSelected) {
            conversationSelectionVersion += 1
            cancelHistoryPageLoad()
            conversationSelectionJob?.cancel()
            isLoadingConversation = false
            conversationLoadFailed = false
            fileAttachmentOwnerVersion += 1
            val nextId = conversationsById.keys.firstOrNull()
            if (nextId != null) {
                selectedConversationId = nextId
                homeState = conversationsById.getValue(nextId)
            } else {
                selectedConversationId = null
                homeState = newChatState()
            }
            if (nextId != null) selectConversation(nextId) else refreshConversationModel()
        }
        conversationPaneState = conversationPaneState.copy(selectedConversationId = selectedConversationId)
        refreshConversationSummaries()
        persistConversations()
    }

    private fun abortPendingConversationDelete(conversationId: String) {
        if (pendingConversationDeletes.remove(conversationId)) {
            Toast.makeText(appContext, R.string.conversation_delete_save_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun renameConversation(conversationId: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        conversationTitles = conversationTitles + (conversationId to trimmed)
        conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        refreshConversationSummaries()
        persistConversations()
    }

    suspend fun exportConversationMarkdown(conversationId: String): String? {
        if (conversationId !in conversationsById) return null
        if (!persistConversations().await()) error(appContext.getString(R.string.conversation_persistence_failed))
        val title = conversationTitles[conversationId]?.takeIf { it.isNotBlank() }
            ?: appContext.getString(R.string.conversation_unnamed)
        return withContext(Dispatchers.IO) { ConversationMarkdownExporter.export(
            title = title,
            messages = AgentConversationStore.loadAllMessages(appContext, conversationId),
            subagents = exportSubagentSections(conversationId),
            labels = ConversationMarkdownExporter.Labels(
                subagentsHeading = appContext.getString(R.string.conversation_export_subagents),
                user = appContext.getString(R.string.conversation_export_user),
                assistant = appContext.getString(R.string.conversation_export_assistant),
                thinking = appContext.getString(R.string.conversation_export_thinking),
                toolLineFormat = appContext.getString(R.string.conversation_export_tool_line),
                toolsLineFormat = appContext.getString(R.string.conversation_export_tools_line),
                argumentsFormat = appContext.getString(R.string.conversation_export_tool_arguments),
                resultFormat = appContext.getString(R.string.conversation_export_tool_result),
                imagesFormat = appContext.getString(R.string.conversation_export_images),
                toolStatusRunning = appContext.getString(R.string.tool_status_running),
                toolStatusSuccess = appContext.getString(R.string.tool_status_success),
                toolStatusFailed = appContext.getString(R.string.tool_status_failed),
                toolStatusUnknown = appContext.getString(R.string.tool_status_unknown),
                noticeStopped = noticeText(SystemNoticeCode.Stopped),
                noticeEmptyResult = noticeText(SystemNoticeCode.EmptyResult),
                noticeModelRetry = noticeText(SystemNoticeCode.ModelRetry),
                noticeModelFallback = noticeText(SystemNoticeCode.ModelFallback),
                noticeContextCompaction = noticeText(SystemNoticeCode.ContextCompaction),
                noticeRuntimeFailed = noticeText(SystemNoticeCode.RuntimeFailed),
                noticeInterrupted = noticeText(SystemNoticeCode.Interrupted),
                noticeSubagentNotified = noticeText(SystemNoticeCode.SubagentNotified),
            ),
        ) }
    }

    /** 导出时附上的会话级子 Agent 过程（AG-27）：每个子 Agent 一节，时间线与过程窗口相同（按回合分段）。 */
    private suspend fun exportSubagentSections(conversationId: String): List<ConversationMarkdownExporter.SubagentSection> =
        runCatching { EtaDatabase.get(appContext).subagentDao().agents(conversationId) }.getOrDefault(emptyList()).map { row ->
            ConversationMarkdownExporter.SubagentSection(
                heading = (if (row.name == row.agentId) row.agentId else "${row.name}（${row.agentId}）") + " · " + row.status,
                task = row.task,
                timeline = SubagentDetails.timeline(AgentConversationCodec.decodeTranscript(row.transcriptJson)),
            )
        }

    private fun noticeText(code: SystemNoticeCode): String = appContext.getString(
        when (code) {
            SystemNoticeCode.Stopped -> R.string.system_notice_stopped
            SystemNoticeCode.EmptyResult -> R.string.system_notice_empty_result
            SystemNoticeCode.ContextCompaction -> R.string.context_compaction
            SystemNoticeCode.ModelRetry -> R.string.system_notice_model_retry
            SystemNoticeCode.ModelFallback -> R.string.system_notice_model_fallback
            SystemNoticeCode.RuntimeFailed -> R.string.system_notice_runtime_failed
            SystemNoticeCode.Interrupted -> R.string.system_notice_interrupted
            SystemNoticeCode.SubagentNotified -> R.string.system_notice_subagent_notified
        },
    )

    /** 发送；回复中直接发送的文字作为追加要求交给当前回复，不打断（ENG-6 D2）。 */
    fun sendCurrentMessage(submittedText: String? = null) {
        submitOrQueueCurrentMessage(submittedText = submittedText, queueWhileStreaming = false)
    }

    /**
     * 输入栏长按发送。上游语义是停掉当前回复后立即发送；本分支改为排进队列，
     * 本次回复结束后发出（ENG-6 D2）。名称沿用上游，便于以后同步。
     */
    fun interruptAndSendCurrentMessage(submittedText: String? = null) {
        submitOrQueueCurrentMessage(submittedText = submittedText, queueWhileStreaming = true)
    }

    /** 队列里点闪电：回复中把这条排队消息立即作为追加交给当前回复（不打断）；空闲时直接发送。 */
    fun steerQueuedMessage(id: String) {
        if (!homeState.isConversationLoaded) return
        if (homeState.isStreaming && !homeState.isCompacting) {
            val item = homeState.queuedMessages.firstOrNull { it.id == id } ?: return
            if (item.pendingImages.isNotEmpty() || item.pendingFileReferences.isNotEmpty()) {
                Toast.makeText(appContext, appContext.getString(R.string.chat_follow_up_queued), Toast.LENGTH_SHORT).show()
                return
            }
            val remaining = AgentMessageQueueReducer.remove(homeState, id)
            if (steerCurrentRun(item.content)) updateCurrentConversation(remaining, updateTimestamp = false)
            return
        }
        val (nextState, promoted) = AgentMessageQueueReducer.promoteToInterrupt(homeState, id)
        if (promoted == null) return
        updateCurrentConversation(nextState, updateTimestamp = false)
        if (nextState.isStreaming && !nextState.isCompacting) {
            requestInterruptCurrentRun()
        } else if (!nextState.isStreaming) {
            dispatchQueuedMessage(selectedConversationId, promoted)
        }
    }

    fun recallQueuedMessage(id: String, currentDraftText: String) {
        if (!homeState.isConversationLoaded) return
        val nextState = AgentMessageQueueReducer.recallToDraft(
            state = homeState,
            currentInputText = currentDraftText,
            id = id,
        ) ?: return
        updateCurrentConversation(nextState, updateTimestamp = false)
    }

    fun removeQueuedMessage(id: String) {
        if (!homeState.isConversationLoaded) return
        val nextState = AgentMessageQueueReducer.remove(homeState, id)
        if (nextState !== homeState) {
            updateCurrentConversation(nextState, updateTimestamp = false)
        }
    }

    private fun submitOrQueueCurrentMessage(
        submittedText: String?,
        queueWhileStreaming: Boolean,
    ) {
        if (!homeState.isConversationLoaded || homeState.isCompacting ||
            selectedConversationId in pendingConversationDeletes) return
        val prompt = (submittedText ?: homeState.input).trim()
        val pendingImages = homeState.pendingImages
        val pendingFileReferences = homeState.pendingFileReferences
        if (prompt.isBlank() && pendingImages.isEmpty() && pendingFileReferences.isEmpty()) {
            return
        }
        if (!homeState.isStreaming) {
            homeState.messageEdit?.takeIf { it.preserveFollowingMessages }?.let { edit ->
                val updated = RoleplayConversationReducer.edit(homeState, edit.targetMessageId, prompt) ?: return
                updateCurrentConversation(updated.copy(
                    input = edit.previousInput,
                    pendingImages = edit.previousImages,
                    pendingFileReferences = edit.previousFileReferences,
                    messageEdit = null,
                ))
                refreshConversationSummaries()
                persistConversations()
                return
            }
        }
        val fileReferences = pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        if (homeState.isStreaming) {
            // 纯文字的直接发送是追加要求；长按、带附件或不能追加时排队，本次回复结束后发出。
            val attachments = pendingImages.isNotEmpty() || pendingFileReferences.isNotEmpty()
            if (!queueWhileStreaming && !attachments && prompt.isNotBlank() && steerCurrentRun(prompt)) {
                if (submittedText == null) updateCurrentConversation(homeState.copy(input = ""), updateTimestamp = false)
                return
            }
            val queued = QueuedMessageUi(
                id = "queue-${UUID.randomUUID()}",
                content = prompt,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                immediateInterrupt = false,
            )
            updateCurrentConversation(
                state = AgentMessageQueueReducer.enqueue(homeState, queued),
                updateTimestamp = false,
            )
            return
        }

        val edit = homeState.messageEdit
        if (edit == null && selectedConversationId?.isReadOnlyExternalArchiveConversation() == true) {
            moveCurrentDraftToNewConversation()
        }

        val editBoundary = edit?.let {
            AgentConversationRevisionReducer.boundary(homeState, it.targetMessageId)
        }
        if (edit != null && editBoundary == null) {
            cancelMessageEdit()
            return
        }

        val conversationId = selectedConversationId ?: newConversationId().also {
            selectedConversationId = it
        }
        dispatchMessageToConversation(
            conversationId = conversationId,
            state = homeState,
            prompt = prompt,
            pendingImages = pendingImages,
            pendingFileReferences = pendingFileReferences,
            editBoundary = editBoundary,
        )
    }

    private fun dispatchQueuedMessage(
        conversationId: String?,
        item: QueuedMessageUi,
    ) {
        val targetConversationId = conversationId ?: return
        if (targetConversationId in pendingConversationDeletes) return
        val state = conversationsById[targetConversationId] ?: return
        if (state.isStreaming || state.isCompacting) return
        val fileReferences = item.pendingFileReferences.map { it.reference }
        if (
            !AgentFileReferencePolicy.canSend(
                references = fileReferences,
                terminalToolsEnabled = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
            )
        ) {
            Toast.makeText(
                appContext,
                appContext.getString(R.string.state_ui_file_path_reference_requires_opening_the_termina_deca4c),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        dispatchMessageToConversation(
            conversationId = targetConversationId,
            state = state,
            prompt = item.content,
            pendingImages = item.pendingImages,
            pendingFileReferences = item.pendingFileReferences,
            editBoundary = null,
            preserveCurrentDraft = true,
        )
    }

    private fun dispatchMessageToConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        prompt: String,
        pendingImages: List<PendingImageUi>,
        pendingFileReferences: List<PendingFileReferenceUi>,
        editBoundary: AgentConversationRevisionReducer.Boundary?,
        preserveCurrentDraft: Boolean = false,
    ) {
        val fileReferences = pendingFileReferences.map { it.reference }
        val runtimePrompt = AgentFileReferencePromptCodec.format(prompt, fileReferences)
        val runId = "run-${UUID.randomUUID()}"
        val userMessage = UserMessageUi(
            id = editBoundary?.userMessage?.id ?: "user-$runId",
            content = runtimePrompt,
            images = pendingImages.map { it.dataUrl },
            isEdited = editBoundary != null,
        )
        val history = editBoundary?.historyPrefix ?: state.history
        val messages = if (editBoundary == null) {
            state.messages + userMessage
        } else {
            state.messages.take(editBoundary.userMessageIndex) + userMessage
        }
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = runtimePrompt,
            images = pendingImages.toHistoryImages(),
        ).copy(messageId = userMessage.id)

        val currentTitle = conversationTitles[conversationId]
        val oldAutoTitle = editBoundary
            ?.takeIf { it.userMessageIndex == 0 }
            ?.userMessage
            ?.content
            ?.defaultConversationTitleFromMessage()
        val nextAutoTitle = defaultConversationTitle(prompt, fileReferences)
        val title = if (
            editBoundary?.userMessageIndex == 0 &&
            (currentTitle == oldAutoTitle || currentTitle.isNullOrBlank())
        ) {
            nextAutoTitle
        } else {
            currentTitle?.takeIf(String::isNotBlank) ?: nextAutoTitle
        }

        conversationTitles = conversationTitles + (conversationId to title)
        if (selectedConversationId == conversationId) {
            conversationPaneState = conversationPaneState.copy(selectedConversationId = conversationId)
        }
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = runtimePrompt,
            images = pendingImages,
            history = history,
            userHistoryMessage = userHistoryMessage,
            messages = messages,
            state = if (preserveCurrentDraft) {
                state.copy(
                    journal = editBoundary?.journalPrefix ?: state.journal,
                    messageEdit = null,
                )
            } else {
                state.copy(
                    journal = editBoundary?.journalPrefix ?: state.journal,
                    input = "",
                    pendingImages = emptyList(),
                    pendingFileReferences = emptyList(),
                    messageEdit = null,
                )
            },
            reasoningEffort = state.reasoningEffort,
        )
    }

    fun beginMessageEdit(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        if (homeState.roleplay != null) {
            if (messageId !in homeState.roleplayMessages.links) return
            val content = when (val message = homeState.messages.firstOrNull { it.id == messageId }) {
                is UserMessageUi -> message.content
                is AgentMessageUi -> message.content
                else -> return
            }
            updateCurrentConversation(homeState.copy(
                input = content, pendingImages = emptyList(), pendingFileReferences = emptyList(),
                messageEdit = MessageEditUiState(messageId, homeState.input, homeState.pendingImages,
                    homeState.pendingFileReferences, hasLaterTurns = false, preserveFollowingMessages = true),
            ))
            return
        }
        val boundary = revisionBoundary(messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "edit-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val parsedPrompt = AgentFileReferencePromptCodec.parse(boundary.userMessage.content)
        val fileReferences = parsedPrompt.references.mapIndexed { index, reference ->
            PendingFileReferenceUi(
                id = "edit-${boundary.userMessage.id}-file-$index",
                reference = reference,
            )
        }
        updateCurrentConversation(
            homeState.copy(
                input = parsedPrompt.request,
                pendingImages = images,
                pendingFileReferences = fileReferences,
                messageEdit = MessageEditUiState(
                    targetMessageId = boundary.userMessage.id,
                    previousInput = homeState.input,
                    previousImages = homeState.pendingImages,
                    previousFileReferences = homeState.pendingFileReferences,
                    hasLaterTurns = boundary.laterTurnCount > 0,
                ),
            )
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
    }

    fun cancelMessageEdit() {
        val edit = homeState.messageEdit ?: return
        updateCurrentConversation(
            homeState.copy(
                input = edit.previousInput,
                pendingImages = edit.previousImages,
                pendingFileReferences = edit.previousFileReferences,
                messageEdit = null,
            )
        )
    }

    fun messageRevisionImpact(messageId: String): MessageRevisionImpact? =
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) MessageRevisionImpact(0)
        else revisionBoundary(messageId)?.let { boundary ->
            MessageRevisionImpact(laterTurnCount = boundary.laterTurnCount)
        }

    private fun revisionBoundary(messageId: String): AgentConversationRevisionReducer.Boundary? =
        AgentConversationRevisionReducer.boundary(homeState, messageId).also { boundary ->
            if (boundary == null && homeState.hasEarlierMessages) {
                Toast.makeText(appContext, R.string.conversation_load_turn_start, Toast.LENGTH_SHORT).show()
            }
        }

    fun deleteMessageTurn(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null && messageId.startsWith("greeting-")) {
            val originalId = homeState.roleplayMessages.links[messageId]?.transcriptMessageId ?: return
            val updated = homeState.copy(
                messages = homeState.messages.filterNot { it.id == messageId },
                journal = homeState.journal.filterNot { it.messageId == originalId },
                history = homeState.history.filterNot { it.messageId == originalId || it.contextSummary },
                roleplayMessages = homeState.roleplayMessages.copy(
                    links = homeState.roleplayMessages.links - messageId,
                    revisions = homeState.roleplayMessages.revisions - messageId,
                ),
            )
            val history = RoleplayConversationReducer.projectJournal(updated) ?: return
            updateConversation(conversationId, updated.copy(history = history))
            refreshConversationSummaries()
            persistConversations()
            return
        }
        if (revisionBoundary(messageId) == null) return
        val removed = AgentConversationRevisionReducer.deleteFromTurn(homeState, messageId) ?: return
        val removedIds = homeState.messages.mapTo(mutableSetOf()) { it.id }.apply {
            removeAll(removed.messages.map { it.id }.toSet())
        }
        val revised = if (removed.roleplay == null) removed else removed.copy(roleplayMessages = removed.roleplayMessages.copy(
            links = removed.roleplayMessages.links.filterKeys { it !in removedIds },
            revisions = removed.roleplayMessages.revisions.filterKeys { it !in removedIds },
        ))
        if (revised.messages.isEmpty() && !revised.hasEarlierMessages) {
            conversationsById = conversationsById - conversationId
            conversationTitles = conversationTitles - conversationId
            conversationUpdatedAt = conversationUpdatedAt - conversationId
            fileAttachmentOwnerVersion += 1
            selectedConversationId = null
            homeState = newChatState()
            refreshConversationModel()
            conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
            refreshConversationSummaries()
            persistConversations()
            return
        }
        updateConversation(conversationId, revised)
        refreshConversationSummaries()
        persistConversations()
    }

    fun regenerateMessage(messageId: String) {
        if (homeState.isStreaming || homeState.messageEdit != null) return
        val conversationId = selectedConversationId ?: return
        if (homeState.roleplay != null) {
            val target = homeState.messages.filterIsInstance<AgentMessageUi>().firstOrNull { it.id == messageId } ?: return
            val prefix = RoleplayConversationReducer.rewriteHistory(homeState, messageId) ?: return
            val runId = "run-${UUID.randomUUID()}"
            launchConversationRun(
                conversationId, runId, target.content, emptyList(), prefix, null, homeState.messages,
                homeState.copy(roleplayMessages = homeState.roleplayMessages.copy(
                    pendingRewrites = homeState.roleplayMessages.pendingRewrites + (runId to messageId),
                )), homeState.reasoningEffort, operation = AgentRuntimeWire.OP_REWRITE_REPLY,
                rewriteTargetMessageId = messageId,
            )
            return
        }
        val boundary = revisionBoundary(messageId) ?: return
        val images = boundary.userMessage.images.mapIndexed { index, dataUrl ->
            PendingImageUi(
                id = "regenerate-${boundary.userMessage.id}-$index",
                uri = dataUrl,
                dataUrl = dataUrl,
                mimeType = dataUrl.imageMimeType(),
            )
        }
        val runId = "run-${UUID.randomUUID()}"
        val userHistoryMessage = AgentModelClient.buildUserHistoryMessage(
            text = boundary.userMessage.content,
            images = images.toHistoryImages(),
        )
        if (boundary.contextWasCompacted) showCompactedRevisionNotice()
        launchConversationRun(
            conversationId = conversationId,
            runId = runId,
            prompt = boundary.userMessage.content,
            images = images,
            history = boundary.historyPrefix,
            userHistoryMessage = userHistoryMessage,
            messages = homeState.messages.take(boundary.userMessageIndex + 1),
            state = homeState.copy(journal = boundary.journalPrefix),
            reasoningEffort = homeState.reasoningEffort,
        )
    }

    fun compactCurrentContext() {
        val conversationId = selectedConversationId ?: return
        if (runStore.activeRunId(conversationId) != null || !homeState.canCompactContext || modelPickerState.isChanging) return
        launchConversationRun(
            conversationId = conversationId,
            runId = java.util.UUID.randomUUID().toString(),
            prompt = "", images = emptyList(), history = homeState.history,
            userHistoryMessage = null, messages = homeState.messages, state = homeState,
            reasoningEffort = homeState.reasoningEffort, operation = AgentRuntimeWire.OP_COMPACT,
        )
    }

    private fun launchConversationRun(
        conversationId: String,
        runId: String,
        prompt: String,
        images: List<PendingImageUi>,
        history: List<AgentModelClient.ConversationMessage>,
        userHistoryMessage: AgentModelClient.ConversationMessage?,
        messages: List<AgentChatMessageUi>,
        state: AgentChatHomeUiState,
        reasoningEffort: ReasoningEffort,
        operation: String = AgentRuntimeWire.OP_CHAT,
        rewriteTargetMessageId: String? = null,
    ) {
        if (conversationId in pendingConversationDeletes || !runStore.register(runId, conversationId)) return
        // 发送即绑定：会话记住本轮实际使用的模型，回落到默认模型时也随之更新。
        val runModelId = state.effectiveModelId
        val runReasoningEffort = providers.enabledModel(runModelId)?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)?.normalize(reasoningEffort)
        } ?: ReasoningEffort.OFF

        updateConversation(
            conversationId,
            state.copy(
                modelId = runModelId,
                isStreaming = true,
                history = if (operation == AgentRuntimeWire.OP_REWRITE_REPLY) state.history else history + listOfNotNull(userHistoryMessage),
                journal = state.journal.ifEmpty { state.history } + listOfNotNull(userHistoryMessage),
                isCompacting = operation == AgentRuntimeWire.OP_COMPACT,
                messages = messages,
                messageEdit = null,
            )
        )
        refreshConversationSummaries()
        val initialPersistence = persistConversations()

        val preparationJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // write-ahead：用户消息未提交前不把可能产生副作用的 run 交给 Runtime。
            if (!initialPersistence.await()) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.conversation_persistence_failed),
                        )
                    )
                }
                return@launch
            }
            state.roleplay?.let { binding ->
                try {
                    CharacterRepository.initialize(appContext)
                    CharacterRepository.get(binding.characterId)?.let { profile ->
                        val updatedBinding = binding.copy(
                            cardSnapshotJson = CharacterCardCodec.encodeJson(profile.card),
                            characterName = profile.card.name, avatarPath = profile.avatarPath,
                        )
                        val saved = withContext(Dispatchers.Main) {
                            conversationsById[conversationId]?.let { current ->
                                updateConversation(conversationId, current.copy(roleplay = updatedBinding))
                            }
                            persistConversations()
                        }.await()
                        check(saved) { "无法保存角色会话设定" }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidAgentLogger.warnThrottled("character_run_prepare_failed") {
                        "Character preparation failed: type=${failure.safeLogType()}"
                    }
                    withContext(Dispatchers.Main) {
                        applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "无法读取或保存角色设定，请重试。",
                            operation = operation, rewriteTargetMessageId = rewriteTargetMessageId))
                    }
                    return@launch
                }
            }
            val permittedReasoningEffort = if (
                agentBooleanForUi(Prefs.Keys.AGENT_THINKING_ENABLED)
            ) {
                runReasoningEffort
            } else {
                ReasoningEffort.OFF
            }
            val config = RuntimeConfigRepository.runtimeConfigFor(runModelId)?.copy(
                terminalTools = agentBooleanForUi(Prefs.Keys.AGENT_TERMINAL_TOOLS),
                browserTools = agentBooleanForUi(Prefs.Keys.AGENT_BROWSER_TOOLS),
                deviceDirectTools = agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_DIRECT_TOOLS),
                deviceSensitiveReadTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_READ_TOOLS),
                deviceSensitiveActionTools =
                    agentBooleanForUi(Prefs.Keys.AGENT_DEVICE_SENSITIVE_ACTION_TOOLS),
                thinkingEnabled = permittedReasoningEffort.enablesReasoning,
                reasoningEffort = permittedReasoningEffort,
            )
            if (config == null) {
                withContext(Dispatchers.Main) {
                    applyRunResult(
                        runId,
                        AgentRuntimeWire.RunResult(
                            runId = runId,
                            ok = false,
                            content = "",
                            error = appContext.getString(R.string.state_ui_please_configure_the_model_provider_and_model_fi_a36e15),
                        )
                    )
                }
                return@launch
            }
            val modelImages = images.map { p ->
                AgentModelClient.ModelImage(
                    reference = p.uri,
                    mimeType = p.mimeType,
                    bytes = 0,
                    source = "user_attach",
                )
            }
            if (withContext(Dispatchers.Main) { runStore.isStopRequested(runId) }) {
                withContext(Dispatchers.Main) {
                    applyRunResult(runId, AgentRuntimeWire.RunResult(runId, false, "", "已停止", operation = operation))
                }
                return@launch
            }
            val result = runInterruptible {
                AgentRuntimeClient(appContext, AndroidAgentLogger).run(
                    request = AgentRuntimeWire.RunRequest(
                        operation = operation,
                        rewriteTargetMessageId = rewriteTargetMessageId,
                        runId = runId,
                        prompt = prompt,
                        config = config.copy(supportsSearchSuggestions = true),
                        images = modelImages,
                        history = history,
                        handoff = AgentRuntimeWire.EntryHandoff(
                            id = runId,
                            source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                            payload = conversationId,
                        ),
                    ),
                    onEvent = { event -> enqueueRunEvent(runId, event) },
                )
            }
            withContext(Dispatchers.Main) {
                applyRunResult(runId, result, acknowledgeRuntimeResult = true)
            }
        }
        runStore.observe(runId, preparationJob)
        if (!RootAccess.isGranted) {
            val leaseId = "prepare:$runId"
            val acquired = AgentExecutionService.acquire(appContext, leaseId) {
                scope.launch(Dispatchers.Main.immediate) {
                    if (runId in runStore.observedRunIds) stopRun(runId)
                }
            }
            if (!acquired) {
                preparationJob.cancel()
                applyRunResult(runId, AgentRuntimeWire.RunResult(
                    runId = runId,
                    ok = false,
                    content = "",
                    error = appContext.getString(R.string.capability_background_failed),
                ))
                return
            }
            preparationJob.invokeOnCompletion { AgentExecutionService.release(leaseId) }
        }
        preparationJob.start()
    }

    private fun List<PendingImageUi>.toHistoryImages(): List<AgentModelClient.ModelImage> =
        map { image ->
            AgentModelClient.ModelImage(
                reference = image.dataUrl,
                mimeType = image.mimeType,
                bytes = image.dataUrl.length,
                source = image.uri,
            )
        }

    private fun String.imageMimeType(): String =
        takeIf { startsWith("data:") }
            ?.substringAfter("data:")
            ?.substringBefore(';')
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"

    private fun String.defaultConversationTitle(): String =
        lineSequence().firstOrNull().orEmpty().trim().take(MAX_TITLE_CHARS)

    private fun defaultConversationTitle(
        request: String,
        references: List<AgentFileReference>,
    ): String = AgentFileReferencePolicy
        .titleSource(request, references)
        .defaultConversationTitle()

    private fun String.defaultConversationTitleFromMessage(): String {
        val parsed = AgentFileReferencePromptCodec.parse(this)
        return defaultConversationTitle(parsed.request, parsed.references)
    }

    private fun showCompactedRevisionNotice() {
        Toast.makeText(
            appContext,
            appContext.getString(R.string.state_ui_the_earlier_context_has_been_compressed_and_will_cf6c86),
            Toast.LENGTH_LONG,
        ).show()
    }

    fun attachImage(uri: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val image = AgentImageCodec.fromReference(
                    context = appContext,
                    value = uri,
                    source = "user_attach",
                )
                if (image == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.state_ui_unable_to_read_this_image_please_try_again_or_us_d94978),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@launch
                }
                val preview = AgentImageCodec.previewFromReference(appContext, image) ?: image
                val pending = PendingImageUi(
                    id = "img-${UUID.randomUUID()}",
                    // 后续发送使用首次读取后的稳定引用，不再依赖 ROM Photo Picker URI 的授权生命周期。
                    uri = image.reference,
                    dataUrl = preview.reference,
                    mimeType = image.mimeType,
                )
                withContext(Dispatchers.Main) {
                    updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages + pending))
                }
            } finally {
                val selectedUri = Uri.parse(uri)
                if (selectedUri.scheme == ContentResolver.SCHEME_CONTENT) {
                    runCatching {
                        appContext.contentResolver.releasePersistableUriPermission(
                            selectedUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            }
        }
    }

    fun removePendingImage(id: String) {
        updateCurrentConversation(homeState.copy(pendingImages = homeState.pendingImages.filterNot { it.id == id }))
    }

    fun attachFiles(uris: List<String>) {
        if (uris.isEmpty()) return
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            uris.map { uri ->
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.File,
                )
            }
        }
    }

    fun attachFolder(uri: String) {
        resolveAndAttachFileReferences {
            val gateway = AgentFileReferenceGateway(appContext, AndroidAgentLogger)
            listOf(
                gateway.resolveDocumentUri(
                    uri = Uri.parse(uri),
                    expectedKind = AgentFileReferenceKind.Directory,
                )
            )
        }
    }

    fun attachFilePath(path: String) {
        resolveAndAttachFileReferences {
            listOf(AgentFileReferenceGateway(AndroidAgentLogger).resolveAbsolutePath(path))
        }
    }

    fun removePendingFileReference(id: String) {
        updateCurrentConversation(
            homeState.copy(
                pendingFileReferences = homeState.pendingFileReferences.filterNot { it.id == id }
            )
        )
    }

    private fun resolveAndAttachFileReferences(
        resolver: () -> List<AgentFileReferenceGateway.Resolution>,
    ) {
        val ownerVersion = fileAttachmentOwnerVersion
        scope.launch(Dispatchers.IO) {
            val resolutions = resolver()
            val references = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Success)?.reference
            }
            val failures = resolutions.mapNotNull { resolution ->
                (resolution as? AgentFileReferenceGateway.Resolution.Failure)?.error
            }
            withContext(Dispatchers.Main) {
                if (ownerVersion != fileAttachmentOwnerVersion) {
                    Toast.makeText(appContext, appContext.getString(R.string.state_ui_conversation_switched_selected_path_not_added_5bf91e), Toast.LENGTH_SHORT).show()
                    return@withContext
                }
                val existingPaths = homeState.pendingFileReferences
                    .mapTo(mutableSetOf()) { it.reference.absolutePath }
                val additions = references
                    .distinctBy { it.absolutePath }
                    .filter { existingPaths.add(it.absolutePath) }
                    .map { reference ->
                        PendingFileReferenceUi(
                            id = "file-${UUID.randomUUID()}",
                            reference = reference,
                        )
                    }
                if (additions.isNotEmpty()) {
                    updateCurrentConversation(
                        homeState.copy(
                            pendingFileReferences = homeState.pendingFileReferences + additions
                        )
                    )
                }
                val message = when {
                    failures.size == 1 && references.isEmpty() -> failures.single().userMessage
                    failures.isNotEmpty() -> appContext.resources.getQuantityString(
                        R.plurals.file_references_added_with_failures,
                        failures.size,
                        additions.size,
                        failures.size,
                    )
                    additions.isEmpty() -> appContext.getString(R.string.state_ui_the_selected_path_has_been_added_42b432)
                    else -> null
                }
                if (message != null) {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private val AgentFileReferenceGateway.Error.userMessage: String
        get() = when (this) {
            AgentFileReferenceGateway.Error.UnsupportedDocumentProvider ->
                appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.InvalidPath -> appContext.getString(R.string.state_ui_please_enter_a_valid_absolute_path_6afeb4)
            AgentFileReferenceGateway.Error.PathNotFound -> appContext.getString(R.string.state_ui_the_path_does_not_exist_or_is_no_longer_accessib_a9776e)
            AgentFileReferenceGateway.Error.UnsupportedFileType -> appContext.getString(R.string.state_ui_only_supports_normal_files_and_folders_4adea0)
            AgentFileReferenceGateway.Error.TypeMismatch -> appContext.getString(R.string.state_ui_the_selected_project_type_does_not_match_3a5c49)
            AgentFileReferenceGateway.Error.RootUnavailable -> appContext.getString(R.string.state_ui_root_is_not_available_and_the_path_cannot_be_ver_fc4c81)
            AgentFileReferenceGateway.Error.AccessDenied -> appContext.getString(R.string.capability_import_denied)
            AgentFileReferenceGateway.Error.ImportFailed -> appContext.getString(R.string.capability_import_failed)
            AgentFileReferenceGateway.Error.ImportTooLarge -> appContext.getString(R.string.capability_import_too_large)
            AgentFileReferenceGateway.Error.ValidationTimedOut -> appContext.getString(R.string.state_ui_path_verification_timed_out_please_try_again_703687)
        }

    /**
     * 回复中直接发送的文字交给 Runtime steering，在当前轮次结束后进入下一轮，补充消息由
     * UserSupplementReceived 事件插入会话；有待回答的问题时就是回答。run 已在收尾而拒绝的补充
     * 改为排队。返回 false 表示不能追加（改写回复、已请求停止），由调用方排队。
     */
    private fun steerCurrentRun(prompt: String): Boolean {
        val conversationId = selectedConversationId ?: return false
        val runId = runStore.activeRunId(conversationId) ?: return false
        if (isReplyRewrite(runId) || runStore.isStopRequested(runId)) return false
        conversationStateForRun(runId).pendingQuestion?.takeIf { it.runId == runId }?.let { pending ->
            answerQuestion(pending.questionId, AgentUserAnswer.text(prompt))
            return true
        }
        scope.launch(Dispatchers.IO) {
            val accepted = AgentRuntimeClient(appContext, AndroidAgentLogger).steer(runId, prompt)
            if (!accepted) withContext(Dispatchers.Main) { queueRejectedSteer(conversationId, prompt) }
        }
        return true
    }

    /** 追加被拒绝的文字放到队尾，不动用户此时的草稿；回复已经结束时直接发出。 */
    private fun queueRejectedSteer(conversationId: String, prompt: String) {
        val state = conversationsById[conversationId] ?: return
        val queued = state.copy(queuedMessages = state.queuedMessages + QueuedMessageUi(
            id = "queue-${UUID.randomUUID()}",
            content = prompt,
            pendingImages = emptyList(),
            pendingFileReferences = emptyList(),
            immediateInterrupt = false,
        ))
        updateConversation(conversationId, queued, updateTimestamp = false)
        Toast.makeText(appContext, appContext.getString(R.string.chat_follow_up_queued), Toast.LENGTH_SHORT).show()
        if (queued.isStreaming || runStore.activeRunId(conversationId) != null) return
        val (afterPop, next) = AgentMessageQueueReducer.pollNextToSendAfterRun(queued, stoppedByUser = false)
        updateConversation(conversationId, afterPop, updateTimestamp = false)
        next?.let { dispatchQueuedMessage(conversationId, it) }
    }

    fun stopCurrentRun() {
        val runId = runStore.activeRunId(selectedConversationId) ?: return
        stopRun(runId)
    }

    private fun stopRun(runId: String) {
        conversationIdForRun(runId)?.let { id ->
            conversationsById[id]?.let { state ->
                val cleared = AgentMessageQueueReducer.clearInterruptFlags(state)
                if (cleared !== state) {
                    updateConversation(id, cleared, updateTimestamp = false)
                }
            }
        }
        requestCancelRun(runId, byUser = true)
    }

    private fun requestInterruptCurrentRun() {
        val runId = runStore.activeRunId(selectedConversationId) ?: return
        requestCancelRun(runId)
    }

    private fun requestCancelRun(runId: String, byUser: Boolean = false) {
        if (!runStore.requestStop(runId, byUser)) return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
        }
    }

    private fun isReplyRewrite(runId: String): Boolean =
        conversationsById[conversationIdForRun(runId)]?.roleplayMessages?.pendingRewrites?.containsKey(runId) == true

    private fun restoreRunEvents(runId: String, events: List<AgentEvent>) {
        if (isReplyRewrite(runId)) return
        // 恢复是完整快照：先清除同一 run 的旧投影，再一次发布，避免历史增量重复追加
        // 或中途的 Running 状态使已结束的思考重新展开、播放动画。
        Snapshot.withMutableSnapshot {
            flushPendingRunDelta(runId)
            updateMessages(runId, updateTimestamp = false) { messages ->
                runMessageProjector.resetForReplay(
                    runId = runId,
                    messages = messages,
                    replaySupplementIndexes = events.filterIsInstance<AgentEvent.UserSupplementReceived>()
                        .mapTo(mutableSetOf()) { it.index },
                )
            }
            events.forEach { event -> applyRunEvent(runId, event, persistSupplement = false) }
        }
    }

    private fun enqueueRunEvent(runId: String, event: AgentEvent) {
        runStore.enqueue(runId, event)
    }

    private fun flushPendingRunDelta(runId: String) {
        runStore.flush(runId)
    }

    private fun applyRunEvent(
        runId: String,
        event: AgentEvent,
        persistSupplement: Boolean = true,
    ) {
        if (isReplyRewrite(runId)) {
            if (event is AgentEvent.RunStarted && runStore.isStopRequested(runId)) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            return
        }
        when (event) {
            is AgentEvent.GuiControlChanged -> {
                runStore.updateGuiStatus(runId, event.status)
                refreshConversationSummaries()
            }
            is AgentEvent.UserSupplementReceived ->
                insertSupplementMessage(runId, event.index, event.text, persist = persistSupplement)
            is AgentEvent.UserQuestionAsked -> setPendingQuestion(
                runId,
                AgentUserQuestion.parse(event.questionJson)?.let { PendingQuestion(runId, event.questionId, it) },
            )
            is AgentEvent.UserQuestionResolved ->
                if (conversationStateForRun(runId).pendingQuestion?.questionId == event.questionId) {
                    setPendingQuestion(runId, null)
                }
            is AgentEvent.RunStarted -> if (runStore.isStopRequested(runId)) scope.launch(Dispatchers.IO) {
                AgentRuntimeClient(appContext, AndroidAgentLogger).cancelRun(runId)
            }
            // 流式增量只刷新消息，不推进会话时间戳，也不重算侧栏摘要；其余可见变化两者都更新。
            is AgentEvent.AssistantBlockDelta -> updateMessages(runId, updateTimestamp = false) { messages ->
                runMessageProjector.applyEvent(runId, event, messages)
            }
            else -> updateRunTrace(runId) { messages -> runMessageProjector.applyEvent(runId, event, messages) }
        }
    }

    private fun setPendingQuestion(runId: String, question: PendingQuestion?) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        if (state.pendingQuestion == question) return
        updateConversation(conversationId, state.copy(pendingQuestion = question), updateTimestamp = false)
    }

    /** 选项、跳过与输入框文字都交给 Runtime 裁决；面板随 UserQuestionResolved 事件收起。 */
    fun answerQuestion(questionId: String, answer: AgentUserAnswer) {
        val pending = homeState.pendingQuestion?.takeIf { it.questionId == questionId } ?: return
        scope.launch(Dispatchers.IO) {
            AgentRuntimeClient(appContext, AndroidAgentLogger).answerQuestion(pending.runId, questionId, answer)
        }
    }

    private fun applyRunResult(
        runId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean = false,
    ) {
        flushPendingRunDelta(runId)
        val completedConversationId = conversationIdForRun(runId) ?: return
        if (!runStore.beginSettlement(runId)) return
        setPendingQuestion(runId, null)
        val explicitlyStoppedByUser = runStore.wasStoppedByUser(runId)
        val rewriting = result.operation == AgentRuntimeWire.OP_REWRITE_REPLY || isReplyRewrite(runId)
        if (rewriting) {
            val conversationId = conversationIdForRun(runId)
            val existing = conversationsById[conversationId]
            if (conversationId != null && existing != null && result.contextSnapshotRef.isBlank()) {
                updateConversation(conversationId, RoleplayConversationReducer.applyRewrite(existing, runId, result).copy(isStreaming = true))
            }
            runMessageProjector.clearRun(runId)
            refreshConversationSummaries()
            persistRunResult(runId, completedConversationId, result, acknowledgeRuntimeResult,
                explicitlyStoppedByUser, resumeQueue = false)
            return
        }
        updateRunTrace(runId) { messages ->
            runMessageProjector.finishContextCompaction(runId,
                runMessageProjector.finalizeRun(runId, messages),
                if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩已停止")
        }
        if (result.contextSnapshotRef.isBlank()) {
            applyConversationHistoryResult(runId, result.transcript, result.contextSnapshot, !result.ok || result.contextSnapshot != null)
        }
        when {
            result.operation == AgentRuntimeWire.OP_COMPACT ||
                conversationsById[conversationIdForRun(runId)]?.isCompacting == true -> updateMessages(runId) { messages ->
                    AgentRunMessageProjector.mergeCompactionResultNotice(
                        runId = runId,
                        messages = messages,
                        ok = result.ok,
                        detail = if (result.ok) "上下文压缩完成" else result.error ?: "上下文压缩失败",
                    )
                }
            else -> {
                val notice = when {
                    result.ok && result.content.isNotBlank() -> null
                    result.ok -> SystemNoticeCode.EmptyResult
                    result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED ->
                        SystemNoticeCode.Stopped
                    else -> SystemNoticeCode.RuntimeFailed
                }
                // 手动停止后也能点“继续”：告诉模型停在哪个工具、哪一步，以及停止前说了一半的话。
                if (notice == SystemNoticeCode.Stopped && result.operation == AgentRuntimeWire.OP_CHAT) {
                    appendStopNote(runId, result.transcript)
                }
                updateMessages(runId) { messages ->
                    AgentRunMessageProjector.applyResult(
                        runId, messages, result.content, notice,
                        detail = result.error.takeIf { notice == SystemNoticeCode.RuntimeFailed },
                    )
                }
            }
        }
        conversationIdForRun(runId)?.let { id -> conversationsById[id]?.let {
            updateConversation(id, RoleplayConversationReducer.linkRun(it, runId))
        } }
        runMessageProjector.clearRun(runId)
        refreshConversationSummaries()
        persistRunResult(runId, completedConversationId, result, acknowledgeRuntimeResult,
            explicitlyStoppedByUser, resumeQueue = true)
    }

    private fun appendStopNote(runId: String, transcript: List<AgentModelClient.ConversationMessage>) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val noteId = AgentInterruptionNote.messageId(runId)
        if (state.history.any { it.messageId == noteId }) return
        val replies = state.messages.filterIsInstance<AgentMessageUi>().map { it.id to it.content }
        val note = AgentInterruptionNote.build(
            runId = runId,
            interruptedAt = System.currentTimeMillis(),
            cause = "用户手动停止",
            transcript = transcript,
            partialReply = AgentInterruptionNote.partialReplyFromReplies(runId, transcript, replies),
        )
        updateConversation(conversationId, state.copy(history = state.history + note))
    }

    private fun persistRunResult(
        runId: String,
        conversationId: String,
        result: AgentRuntimeWire.RunResult,
        acknowledgeRuntimeResult: Boolean,
        explicitlyStoppedByUser: Boolean,
        resumeQueue: Boolean,
    ) {
        var acknowledged = !acknowledgeRuntimeResult
        val saved = persistConversations(
            onSaved = if (acknowledgeRuntimeResult && result.contextSnapshotRef.isBlank()) {
                {
                    acknowledged = AgentRuntimeClient(appContext, AndroidAgentLogger).ackResult(runId)
                }
            } else {
                null
            }
        )
        scope.launch {
            val resultSaved = saved.await() && result.contextSnapshotRef.isBlank()
            val stoppedDuringSettlement = runStore.wasStoppedByUser(runId)
            setConversationStreaming(runId, false)
            runStore.remove(runId)
            refreshConversationSummaries()
            if (conversationId in pendingConversationDeletes) {
                if (resultSaved && acknowledged) {
                    deleteSettledConversation(conversationId)
                } else {
                    abortPendingConversationDelete(conversationId)
                }
                return@launch
            }
            if (!resultSaved || !resumeQueue || runStore.activeRunId(conversationId) != null) return@launch
            val finishedState = conversationsById[conversationId]
            if (finishedState != null && finishedState.queuedMessages.isNotEmpty()) {
                val stoppedByUser = explicitlyStoppedByUser || stoppedDuringSettlement ||
                    (!result.ok && (result.error == LEGACY_STOPPED_ERROR || result.error == SYNTHETIC_STATUS_STOPPED))
                val (afterPopState, nextQueued) = AgentMessageQueueReducer.pollNextToSendAfterRun(
                    state = finishedState,
                    stoppedByUser = stoppedByUser,
                )
                updateConversation(conversationId, afterPopState, updateTimestamp = false)
                if (nextQueued != null) {
                    dispatchQueuedMessage(conversationId, nextQueued)
                }
            }
        }
    }

    private fun updateRunTrace(
        runId: String,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ) {
        if (updateMessages(runId, transform = transform)) refreshConversationSummaries()
    }

    private fun insertSupplementMessage(
        runId: String,
        index: Int,
        text: String,
        persist: Boolean = true,
    ) {
        updateMessages(runId) { messages ->
            AgentPendingResultRecovery.mergeSupplements(
                runId = runId,
                supplements = listOf(
                    AgentUiHandoffPayload.Supplement(
                        index = index,
                        text = text,
                        createdAt = System.currentTimeMillis(),
                    )
                ),
                messages = messages,
            )
        }
        refreshConversationSummaries()
        if (persist) persistConversations()
    }

    private fun updateMessages(
        runId: String,
        updateTimestamp: Boolean = true,
        transform: (List<AgentChatMessageUi>) -> List<AgentChatMessageUi>,
    ): Boolean {
        val conversationId = conversationIdForRun(runId) ?: return false
        val state = conversationsById[conversationId] ?: return false
        val messages = transform(state.messages)
        // 投影对无可见变化的事件返回原列表；此时不推进会话时间戳，避免请求开始、轮次开始等事件改变排序。
        if (messages === state.messages) return false
        updateConversation(
            conversationId = conversationId,
            state = state.copy(messages = messages),
            updateTimestamp = updateTimestamp,
        )
        return true
    }

    private fun applyConversationHistoryResult(
        runId: String,
        additions: List<AgentModelClient.ConversationMessage>,
        snapshot: AgentContextSnapshot? = null,
        retainPendingSupplements: Boolean = snapshot != null,
    ) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        val outcome = AgentRuntimeHistoryReducer.apply(state, runId, additions, snapshot, retainPendingSupplements)
        if (!outcome.alreadyApplied) updateConversation(conversationId, outcome.state)
    }

    private fun updateCurrentConversation(
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        val conversationId = selectedConversationId
        if (conversationId == null) {
            homeState = state
        } else {
            updateConversation(conversationId, state, updateTimestamp = updateTimestamp)
        }
    }

    private fun moveCurrentDraftToNewConversation() {
        val draft = homeState
        selectedConversationId = null
        homeState = emptyChatState(defaultThinkingEnabled).copy(
            input = draft.input,
            thinkingEnabled = draft.reasoningEffort.enablesReasoning,
            reasoningEffort = draft.reasoningEffort,
            availableReasoningEfforts = currentReasoningCapabilities?.selectableEfforts.orEmpty(),
            providerDefaultEffort = currentReasoningCapabilities?.defaultEffort,
            pendingImages = draft.pendingImages,
            pendingFileReferences = draft.pendingFileReferences,
            modelId = draft.modelId,
        )
        conversationPaneState = conversationPaneState.copy(selectedConversationId = null)
    }

    private fun updateConversation(
        conversationId: String,
        state: AgentChatHomeUiState,
        updateTimestamp: Boolean = true,
    ) {
        conversationsById = conversationsById + (conversationId to state)
        if (updateTimestamp) {
            conversationUpdatedAt = conversationUpdatedAt + (conversationId to System.currentTimeMillis())
        }
        if (conversationId == selectedConversationId) {
            homeState = state
        }
    }

    private fun setConversationStreaming(runId: String, isStreaming: Boolean) {
        val conversationId = conversationIdForRun(runId) ?: return
        val state = conversationsById[conversationId] ?: return
        updateConversation(conversationId, state.copy(isStreaming = isStreaming, isCompacting = state.isCompacting && isStreaming))
    }

    private fun conversationIdForRun(runId: String): String? = runStore.conversationId(runId)

    private fun conversationStateForRun(runId: String): AgentChatHomeUiState {
        val conversationId = conversationIdForRun(runId) ?: return emptyChatState(defaultThinkingEnabled)
        return conversationsById[conversationId] ?: emptyChatState(defaultThinkingEnabled)
    }

    private fun refreshConversationSummaries() {
        val summaries = conversationsById.entries
            .sortedByDescending { (id, _) ->
                conversationUpdatedAt[id] ?: 0L
            }
            .map { (id, state) ->
                val lastMessage = state.messages.lastOrNull()
                ConversationSummaryUi(
                    id = id,
                    title = conversationTitles[id].orEmpty().ifBlank {
                        appContext.getString(R.string.conversation_unnamed)
                    },
                    preview = when (lastMessage) {
                        is UserMessageUi -> AgentFileReferencePromptCodec
                            .parse(lastMessage.content)
                            .let { parsed ->
                                AgentFileReferencePolicy.titleSource(
                                    request = parsed.request,
                                    references = parsed.references,
                                )
                            }
                        is AgentMessageUi -> lastMessage.content.ifBlank {
                            appContext.getString(R.string.conversation_preview_reasoning)
                        }
                        is SystemNoticeMessageUi -> noticeText(lastMessage.code)
                        is ThinkingMessageUi -> appContext.getString(R.string.conversation_preview_reasoning)
                        is ToolActivityMessageUi -> appContext.getString(
                            R.string.conversation_preview_tool_call,
                            lastMessage.toolName,
                        )
                        else -> appContext.getString(R.string.conversation_preview_empty)
                    }.take(MAX_PREVIEW_CHARS),
                    timeLabel = if (state.isStreaming) {
                        appContext.getString(R.string.time_now)
                    } else {
                        conversationUpdatedAt[id]?.let { timestamp ->
                            ConversationTimeLabels.label(
                                timestampMillis = timestamp,
                                locale = appContext.resources.configuration.locales[0],
                                use24HourClock = DateFormat.is24HourFormat(appContext),
                                yesterdayLabel = appContext.getString(R.string.time_yesterday),
                                recentLabel = appContext.getString(R.string.time_recent),
                            )
                        } ?: appContext.getString(R.string.time_recent)
                    },
                    updatedAtMillis = conversationUpdatedAt[id] ?: 0L,
                    mode = ConversationModeUi.Chat,
                    characterName = state.roleplay?.characterName ?: state.conversationCharacterName,
                    characterAvatarPath = state.roleplay?.avatarPath ?: state.conversationCharacterAvatarPath,
                    isActiveRun = state.isStreaming,
                    entrySource = conversationEntrySource(id, conversationTitles[id].orEmpty()),
                    subagents = conversationSubagents(id, state),
                    hasUnloadedSubagents = !state.isConversationLoaded && subagentCache[id] == null &&
                        id in storedSubagentConversationIds,
                    runStatus = when (runStore.guiStatus(id)) {
                        GuiControlStatus.Waiting -> appContext.getString(R.string.run_waiting_for_gui)
                        GuiControlStatus.UserPaused -> appContext.getString(R.string.run_gui_user_paused)
                        else -> null
                    },
                )
            }
        val query = conversationPaneState.searchQuery.trim()
        conversationPaneState = conversationPaneState.copy(
            selectedConversationId = selectedConversationId,
            conversations = if (query.isBlank()) {
                summaries
            } else {
                contentMatchCache.keys.retainAll(conversationsById.keys)
                summaries.filter { summary ->
                    summary.title.contains(query, ignoreCase = true) ||
                        summary.preview.contains(query, ignoreCase = true) ||
                        summary.id in persistedSearchMatches || conversationContentMatches(summary.id, query)
                }
            },
        )
    }

    /** 旧版 `run_subagents` 留下的子 Agent（从会话记录还原）在前，AG-19 的会话级子 Agent（注册表）在后。 */
    private fun conversationSubagents(conversationId: String, state: AgentChatHomeUiState): List<ConversationSubagentUi> =
        legacySubagents(conversationId, state) + registrySubagents[conversationId].orEmpty()

    private fun legacySubagents(conversationId: String, state: AgentChatHomeUiState): List<ConversationSubagentUi> {
        // 释放后的会话只剩最后一条消息，沿用它加载时算出的结果；从未加载过的由侧栏展开时加载。
        if (!state.isConversationLoaded) return subagentCache[conversationId]?.third.orEmpty()
        val journal = state.journal.ifEmpty { state.history }
        val stored = storedToolRowsFor(conversationId, state)
        subagentCache[conversationId]?.let { (messages, cachedJournal, subagents) ->
            if (messages === state.messages && cachedJournal === journal) return subagents
        }
        val subagents = SubagentDetails.forConversation(state.messages, journal, stored)
        subagentCache.keys.retainAll(conversationsById.keys)
        subagentCache[conversationId] = Triple(state.messages, journal, subagents)
        return subagents
    }

    /**
     * 会话级子 Agent（AG-19）：变化时（去抖）重读注册表摘要刷新侧栏；主对话已结束时，
     * 子 Agent 的结果由这里自动开一个 run 交给主 Agent。启动时补唤醒上次没来得及处理的会话。
     */
    private fun observeSubagentRegistry() {
        val manager = AgentSubagentManager.get(appContext)
        manager.wakeListener = { conversationId -> scope.launch(Dispatchers.Main) { wakeForSubagents(conversationId) } }
        scope.launch {
            // 先刷新再等：子 Agent 持续有进展时也按固定间隔刷新，不会被一直往后推而停在旧状态（AG-27）。
            manager.revision.collect {
                val summaries = withContext(Dispatchers.IO) {
                    runCatching { EtaDatabase.get(appContext).subagentDao().summaries() }.getOrDefault(emptyList())
                }
                withContext(Dispatchers.Main) {
                    registrySubagents = SubagentDetails.fromRegistry(summaries)
                    refreshConversationSummaries()
                }
                delay(SUBAGENT_REGISTRY_REFRESH_MS)
            }
        }
        scope.launch {
            val pending = withContext(Dispatchers.IO) { runCatching { manager.conversationsWithUndeliveredMail() }.getOrDefault(emptyList()) }
            pending.forEach { conversationId -> withContext(Dispatchers.Main) { wakeForSubagents(conversationId) } }
        }
    }

    /**
     * 后台任务面板里用户对子 Agent 的操作（AG-27）：直接发消息（标注为用户）、停止、关闭。
     * 管理器会短暂等待正在收尾的回合，放到 IO 线程；失败时提示原因。
     */
    fun handleSubagentPanelAction(conversationId: String, action: SubagentPanelAction) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val manager = AgentSubagentManager.get(appContext)
                runCatching {
                    when (action) {
                        is SubagentPanelAction.Send -> manager.sendFromUser(conversationId, action.agentId, action.text)
                        is SubagentPanelAction.Stop -> manager.stop(conversationId, action.agentId)
                        is SubagentPanelAction.Close -> manager.close(conversationId, action.agentId)
                    }
                }.getOrNull()
            }
            if (result?.optBoolean("ok") != true) {
                val message = result?.optString("message").orEmpty().ifBlank { appContext.getString(R.string.background_tasks_action_failed) }
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 只唤醒 App 里的普通会话；正在运行、正被删除或已没有待处理通知时不动。 */
    private suspend fun wakeForSubagents(conversationId: String) {
        if (!Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_AUTO_WAKE)) return
        if (conversationId !in conversationsById || conversationId in pendingConversationDeletes) return
        if (runStore.activeRunId(conversationId) != null) return
        val pending = withContext(Dispatchers.IO) {
            runCatching { EtaDatabase.get(appContext).subagentDao().wakeableMailCount(conversationId) }.getOrDefault(0)
        }
        if (pending == 0) return
        withLoadedConversations(setOf(conversationId)) {
            withContext(Dispatchers.Main) {
                val state = conversationsById[conversationId] ?: return@withContext
                if (runStore.activeRunId(conversationId) != null || state.isStreaming) return@withContext
                val runId = "run-${UUID.randomUUID()}"
                val prompt = appContext.getString(R.string.subagent_wake_prompt)
                // 不加用户气泡：通知本身在 run 开始时交给主 Agent，界面显示为系统提示。
                launchConversationRun(
                    conversationId = conversationId,
                    runId = runId,
                    prompt = prompt,
                    images = emptyList(),
                    history = state.history,
                    userHistoryMessage = AgentModelClient.buildUserHistoryMessage(prompt, emptyList()).copy(messageId = "user-$runId"),
                    messages = state.messages,
                    state = state,
                    reasoningEffort = state.reasoningEffort,
                )
            }
        }
    }

    /** 有更早的页没加载、且可能派过子 Agent 时，从库里补全部工具行；查完清掉缓存重算一次。 */
    private fun storedToolRowsFor(conversationId: String, state: AgentChatHomeUiState): List<ToolActivityMessageUi> {
        if (!state.hasEarlierMessages) return emptyList()
        val mayHaveSubagents = conversationId in storedSubagentConversationIds ||
            state.messages.any { it is ToolActivityMessageUi && it.toolName == AgentSubagentTool.TOOL_NAME }
        if (!mayHaveSubagents) return emptyList()
        val window = state.messages.firstOrNull()?.id
        val cached = storedToolRows[conversationId]
        if (cached != null && cached.first == window) return cached.second
        if (storedToolRowsLoading.add(conversationId)) {
            scope.launch {
                val rows = try {
                    AgentConversationStore.toolRows(appContext, conversationId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AndroidAgentLogger.warn("Agent stored tool rows failed: type=${failure.safeLogType()}")
                    emptyList()
                }
                withContext(Dispatchers.Main) {
                    storedToolRowsLoading.remove(conversationId)
                    storedToolRows.keys.retainAll(conversationsById.keys)
                    storedToolRows[conversationId] = window to rows
                    subagentCache.remove(conversationId)
                    refreshConversationSummaries()
                }
            }
        }
        return cached?.second.orEmpty()
    }

    private fun loadStoredSubagentConversations() {
        scope.launch {
            val ids = try {
                AgentConversationStore.subagentConversationIds(appContext)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Agent subagent index failed: type=${failure.safeLogType()}")
                return@launch
            }
            withContext(Dispatchers.Main) {
                storedSubagentConversationIds = ids
                refreshConversationSummaries()
            }
        }
    }

    /** 侧栏展开尚未加载的会话：临时加载以算出子 Agent，结果在会话再次释放后仍保留。 */
    fun loadConversationSubagents(conversationId: String) {
        scope.launch {
            try {
                withLoadedConversations(setOf(conversationId)) {
                    withContext(Dispatchers.Main) { refreshConversationSummaries() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AndroidAgentLogger.warn("Agent subagent load failed: type=${failure.safeLogType()}")
            }
        }
    }

    // 内容匹配按（查询词, 会话状态引用）缓存：刷新摘要时未变化的会话不重复全文扫描。
    private val contentMatchCache = mutableMapOf<String, ContentMatchCacheEntry>()

    private fun conversationContentMatches(conversationId: String, query: String): Boolean {
        val state = conversationsById[conversationId] ?: return false
        val cached = contentMatchCache[conversationId]
        if (cached != null && cached.query == query && cached.state === state) {
            return cached.matches
        }
        val matches = state.contentMatches(query) { code -> noticeText(code) }
        contentMatchCache[conversationId] = ContentMatchCacheEntry(query, state, matches)
        return matches
    }

    private fun persistConversations(onSaved: (() -> Unit)? = null): Deferred<Boolean> {
        val selected = selectedConversationId
        val conversations = conversationsById
        val titles = conversationTitles
        val timestamps = conversationUpdatedAt
        return synchronized(persistenceLock) {
            // 未保存会阻止结果回执和 write-ahead 运行，由导入后的重载或失败后的补写收尾。
            if (persistencePaused) return CompletableDeferred(false)
            val previous = persistenceJob
            val persistence = conversationPersistence
            scope.async(Dispatchers.IO) {
                try {
                    previous?.join()
                    persistence.save(appContext,
                        AgentConversationStore.Snapshot(selected, conversations, titles, timestamps))
                    onSaved?.invoke()
                    scope.launch(Dispatchers.Main.immediate) { releaseInactiveConversations(conversations) }
                    true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (throwable: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent conversation persistence failed: type=${throwable.safeLogType()}"
                    )
                    false
                }
            }.also { persistenceJob = it }
        }
    }

    private companion object {
        const val MAX_TITLE_CHARS = 24
        const val MAX_PREVIEW_CHARS = 48
        const val LEGACY_STOPPED_ERROR = "已停止"
        const val SYNTHETIC_STATUS_STOPPED = "eta_status:stopped"
        /** 子 Agent 每轮都会落盘，侧栏只需去抖后刷新。 */
        const val SUBAGENT_REGISTRY_REFRESH_MS = 1_000L

        fun emptyChatState(thinkingEnabled: Boolean): AgentChatHomeUiState =
            AgentChatHomeUiState(
                messages = emptyList(),
                history = emptyList(),
                journal = emptyList(),
                input = "",
                isStreaming = false,
                thinkingEnabled = thinkingEnabled,
            )

        fun newConversationId(): String = "conv-${UUID.randomUUID()}"
    }
}

internal data class MessageRevisionImpact(
    val laterTurnCount: Int,
)

private data class ContentMatchCacheEntry(
    val query: String,
    val state: AgentChatHomeUiState,
    val matches: Boolean,
)

/** 运行中往前插入一页的前提（AG-29）：窗口起点没变、没有在编辑，且确实还有更早的消息。 */
internal fun AgentChatHomeUiState.canPrependEarlierPage(startIndex: Int): Boolean =
    isConversationLoaded && messageEdit == null && hasEarlierMessages && messageStartIndex == startIndex

internal fun AgentChatHomeUiState.withEarlierPage(page: AgentConversationStore.MessagePage): AgentChatHomeUiState =
    RoleplayConversationReducer.decorate(copy(
        messages = page.messages + messages,
        messageStartIndex = page.startIndex,
        hasEarlierMessages = page.hasEarlierMessages,
    ))

private fun AgentChatHomeUiState.canReplaceMessageWindowOf(saved: AgentChatHomeUiState): Boolean =
    isConversationLoaded && !isStreaming && messageEdit == null &&
        messages === saved.messages && messageStartIndex == saved.messageStartIndex &&
        history === saved.history && journal === saved.journal && roleplayMessages == saved.roleplayMessages

private const val EXTERNAL_ARCHIVE_CONVERSATION_PREFIX = ExternalArchiveIds.EXTERNAL_PREFIX

private fun String.isReadOnlyExternalArchiveConversation(): Boolean =
    startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)

/** 编号算法与自动任务结果通知共用，见 [ExternalArchiveIds]。 */
private fun archiveConversationId(source: String, conversationKey: String): String =
    ExternalArchiveIds.conversationId(source, conversationKey)

private fun legacyArchiveConversationId(source: String, conversationKey: String): String =
    ExternalArchiveIds.legacyConversationId(source, conversationKey)

/** 新编号带来源标记；旧编号与被改名前的标题前缀作为兜底识别。 */
internal fun conversationEntrySource(conversationId: String, title: String): ConversationEntrySource? {
    if (!conversationId.startsWith(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX)) return null
    val tagged = conversationId.removePrefix(EXTERNAL_ARCHIVE_CONVERSATION_PREFIX).substringBefore('-', "")
    return when {
        tagged == AgentRuntimeWire.XIAOAI_HANDOFF_SOURCE || title.startsWith("超级小爱") -> ConversationEntrySource.XiaoAi
        tagged == BREENO_SOURCE_TAG || title.startsWith("小布") -> ConversationEntrySource.Breeno
        tagged == AgentRuntimeWire.PC_MCP_HANDOFF_SOURCE -> ConversationEntrySource.Computer
        tagged == AgentRuntimeWire.AUTOMATION_HANDOFF_SOURCE -> ConversationEntrySource.Automation
        else -> null
    }
}

private const val BREENO_SOURCE_TAG = "breeno"

private fun agentBooleanForUi(key: String): Boolean {
    return Prefs.isEnabled(key)
}
