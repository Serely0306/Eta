package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityKeeper
import io.github.mangi.eta.agent.browser.BrowserSessionRegistry
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentConversationToolCatalog
import io.github.mangi.eta.agent.tool.ConversationHistoryTool
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentModelExecutionException
import io.github.mangi.eta.agent.model.AgentModelFailure
import io.github.mangi.eta.agent.model.ModelUsageContext
import io.github.mangi.eta.agent.model.ModelUsageScope
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentTaskPlan
import io.github.mangi.eta.agent.model.ModelFallbackPolicy
import io.github.mangi.eta.agent.model.AgentUserAnswer
import io.github.mangi.eta.agent.model.AgentUserQuestion
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.memory.AgentMemoryContextBuilder
import io.github.mangi.eta.agent.memory.AgentMemorySessionSnapshots
import io.github.mangi.eta.agent.roleplay.CharacterMemoryTools
import io.github.mangi.eta.agent.roleplay.RoleplayRunContext
import io.github.mangi.eta.agent.mcp.McpRunSnapshot
import io.github.mangi.eta.agent.mcp.McpToolExecutor
import io.github.mangi.eta.agent.mcp.RoutingToolExecutor
import io.github.mangi.eta.agent.mcp.server.EtaMcpModelAccess
import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import io.github.mangi.eta.agent.skill.SkillCompatibilityChecker
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.tool.AgentLocalTools
import io.github.mangi.eta.agent.tool.AgentToolRequirements
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.tool.AgentToolToggles
import io.github.mangi.eta.agent.tool.PendingSkillConflictCapabilityParser
import io.github.mangi.eta.agent.tool.ToolExecutionDecision
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.AgentMemoryRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Runtime run 的阻塞执行器。
 *
 * 它只拥有模型、工具和终态提交，不持有 Service、Messenger、Compose 或 WindowManager 状态。
 * 所有外部副作用都通过窄回调交回宿主。
 */
internal class AgentRuntimeRunExecutor(
    context: Context,
    private val currentPermissions: () -> AgentRuntimePolicy.Permissions,
    private val snapshotRequest: (AgentRuntimeWire.RunRequest) -> AgentRuntimeWire.RunRequest,
    private val guiControl: GuiControlCoordinator,
    private val onAcceptedEvent: (AgentEvent, EntrySurfaceGuard?) -> Unit,
    private val persistArtifacts: (
        AgentRuntimeWire.RunRequest,
        AgentRuntimeWire.RunResult,
        List<AgentEvent>,
    ) -> Unit,
) {
    data class Outcome(
        val result: AgentRuntimeWire.RunResult,
        val entrySurfaceGuard: EntrySurfaceGuard?,
        val completedRequest: AgentRuntimeWire.RunRequest? = null,
        val response: AgentModelClient.ModelResponse.Text? = null,
        val shouldUpdateHost: Boolean,
    )

    private val appContext = context.applicationContext

    fun execute(
        session: AgentRuntimeSession,
        request: AgentRuntimeWire.RunRequest,
    ): Outcome {
        val runController = session.controller
        val archivedEvents = AgentRunArchiveStore.createEventBuffer(request.handoff)
        var entrySurfaceGuard: EntrySurfaceGuard? = null
        var toolExecutor: AutoCloseable? = null
        var toolsBinding: AgentRunController.ResourceBinding? = null
        var guiRun: GuiControlCoordinator.RunControl? = null
        var browserBinding: AutoCloseable? = null
        var response: AgentModelClient.ModelResponse.Text? = null
        var cancelled = false
        var checkpointRecorder: AgentRunCheckpointRecorder? = null
        val timing = AgentRunTiming(AndroidAgentLogger)
        // 子 Agent 属于会话：只有普通对话的 run 接收它们的完成通知；压缩与改写不派也不接收。
        val subagentManager = AgentSubagentManager.get(appContext)
        val subagentConversation = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
        val receivesSubagentNotices = request.operation == AgentRuntimeWire.OP_CHAT

        val result = try {
            val contextWindow = request.config.knownContextWindow
            checkpointRecorder = AgentRunCheckpointRecorder.create(appContext, request)
            entrySurfaceGuard = EntrySurfaceGuard.from(
                handoff = request.handoff,
                logger = AndroidAgentLogger,
                etaVoiceSurfaceDismissal = {
                    EtaAssistantOverlayService.dismissForForegroundOperation(appContext)
                },
            )
            val skillIndexService = SkillRuntime.createIndexService(appContext)
            val skillLoader = SkillRuntime.createLoader(appContext)
            val skillResourceReader = SkillRuntime.createResourceReader(appContext)
            val skillPackageInstaller = SkillRuntime.createPackageInstaller(appContext)
            val githubSkillSource = PublicGitHubSkillSource(
                cacheRoot = appContext.cacheDir,
                baseClient = AgentHttpClient.client,
            )
            val skillContext = SkillContext(
                installedSkills = skillIndexService.listInstalledSkills()
                    .filter { SkillCompatibilityChecker.evaluate(it).available },
            )
            val memoryEnabled = runBlocking { AgentMemoryRepository.isEnabled() }
            val uiPayload = request.handoff
                ?.takeIf { it.source == AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE }
                ?.let { AgentUiHandoffPayload.from(it.payload) }
            val conversationId = uiPayload?.conversationId
                ?.takeIf { it.isNotBlank() }
            val roleplayContext = conversationId?.let { id ->
                runBlocking { RoleplayRunContext.resolve(appContext, id, contextWindow, memoryEnabled) }
            }
            if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                require(roleplayContext != null) { "只有角色会话可以改写角色回复" }
                val target = request.rewriteTargetMessageId?.takeIf { it.isNotBlank() && it.length <= 256 }
                    ?: throw IllegalArgumentException("缺少有效的角色回复目标")
                require(runBlocking {
                    EtaDatabase.get(appContext).conversationDao().hasAssistantMessage(conversationId, target)
                }) { "角色回复目标不存在或不属于当前会话" }
            }
            val characterMemoryTools = roleplayContext?.let { roleplay ->
                CharacterMemoryTools(appContext, roleplay.characterId) {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                }
            }
            val memoryContext = if (memoryEnabled) {
                runCatching {
                    val snapshot = AgentMemoryRepository.snapshot()
                    AgentMemorySessionSnapshots.resolve(
                        sessionId = request.effectiveModelSessionId,
                        contextWindow = request.config.contextWindow,
                        externalEditGeneration = AgentMemoryRepository.externalEditGeneration,
                        history = request.history,
                        revision = snapshot.revision,
                    ) {
                        AgentMemoryContextBuilder.build(
                            snapshot = snapshot,
                            contextWindow = request.config.contextWindow,
                        )
                    }
                }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_memory_context_failed") {
                        "Agent memory context unavailable: type=${throwable.safeLogType()}"
                    }
                    AgentMemoryContextBuilder.empty(request.config.contextWindow)
                }
            } else {
                AgentMemoryContext.DISABLED
            }
            if (request.config.terminalTools) {
                // 启动探测由 App 拥有；模型首轮等待其结果，避免把检测中的授权当成普通设备。
                runBlocking {
                    while (io.github.mangi.eta.agent.device.RootAccess.state.value.isChecking) {
                        runController.throwIfCancelled()
                        kotlinx.coroutines.delay(100)
                    }
                }
            }
            val pendingSkillConflict = PendingSkillConflictCapabilityParser.parse(request.history)
            val mcpSnapshot = runBlocking {
                runCatching { McpRunSnapshot.load() }.getOrElse { throwable ->
                    AndroidAgentLogger.warnThrottled("agent_mcp_snapshot_failed") {
                        "MCP tool snapshot unavailable: type=${throwable.safeLogType()}"
                    }
                    McpRunSnapshot.EMPTY
                }
            }
            val mcpTools = JSONArray().also(mcpSnapshot::appendModelTools)
            val disabledTools = { AgentToolToggles.effective(currentPermissions().disabledTools) }
            val browserKey = AgentConversationIdentity.fromHandoff(request.handoff, request.runId)
            browserBinding = BrowserSessionRegistry.acquireRun(browserKey, request.runId)
            guiRun = guiControl.openRun(request.runId, runController) { status ->
                acceptEvent(
                    session, AgentEvent.GuiControlChanged(status), archivedEvents,
                    entrySurfaceGuard, checkpointRecorder,
                )
            }
            // 子 Agent 各自持有执行器，归属用独立 id。界面与浏览器工具不开放给子 Agent，它们不持有
            // 主 run 的手机操作权与会话浏览器，执行器关闭时也就不会释放主 run 的这两项。
            fun newLocalTools(ownerId: String = request.runId) = AgentLocalTools(
                context = appContext,
                logger = AndroidAgentLogger,
                browserRunId = ownerId,
                browserSession = if (ownerId == request.runId) BrowserSessionRegistry.get(browserKey) else null,
                guiControl = if (ownerId == request.runId) guiRun else null,
                browserToolsEnabled = {
                    request.config.browserTools && currentPermissions().browserTools
                },
                terminalToolsEnabled = {
                    request.config.terminalTools && currentPermissions().terminalTools
                },
                deviceDirectToolsEnabled = {
                    request.config.deviceDirectTools && currentPermissions().deviceDirectTools
                },
                deviceSensitiveReadToolsEnabled = {
                    request.config.deviceSensitiveReadTools &&
                        currentPermissions().deviceSensitiveReadTools
                },
                deviceSensitiveActionToolsEnabled = {
                    request.config.deviceSensitiveActionTools &&
                        currentPermissions().deviceSensitiveActionTools
                },
                memoryToolsEnabled = {
                    runBlocking { AgentMemoryRepository.isEnabled() }
                },
                memoryWritable = roleplayContext == null,
                disabledTools = disabledTools,
                screenshotExcludedPackages = {
                    entrySurfaceGuard?.consumeScreenshotExcludedPackages().orEmpty()
                },
                beforeToolExecution = { toolName ->
                    val requiresAccessibility =
                        AgentToolRequirements.requiresAccessibility(toolName)
                    if (
                        !requiresAccessibility &&
                        !AgentOverlayVisibilityPolicy.requiresEntrySurfaceDismissal(toolName)
                    ) {
                        ToolExecutionDecision.Allow
                    } else {
                        val accessibility = if (requiresAccessibility) {
                            AgentAccessibilityKeeper.ensureEnabledForGuiOperation(appContext)
                        } else {
                            null
                        }
                        when {
                            accessibility != null && !accessibility.available ->
                                ToolExecutionDecision.Reject(
                                    code = accessibility.code,
                                    message = accessibility.message,
                                )
                            entrySurfaceGuard?.dismissOnce() == false ->
                                ToolExecutionDecision.Reject(
                                    code = "ENTRY_SURFACE_NOT_READY",
                                    message = "入口窗口关闭未完成；本次工具未执行，请勿在当前任务中重复调用",
                                )
                            else -> ToolExecutionDecision.Allow
                        }
                    }
                },
                skillIndexService = skillIndexService,
                skillLoader = skillLoader,
                skillResourceReader = skillResourceReader,
                githubSkillSource = githubSkillSource,
                skillPackageInstaller = skillPackageInstaller,
                runAvailableSkillIds = skillContext.installedSkills.mapTo(mutableSetOf()) { it.id },
                pendingSkillConflict = pendingSkillConflict,
                // 只有主 Agent 启动的后台命令回报给会话；子 Agent 启动的仍只发系统通知（AG-27）。
                backgroundJobOwner = subagentConversation.takeIf { ownerId == request.runId && receivesSubagentNotices },
            )
            val mainLocalTools = newLocalTools()
            val routingExecutor = RoutingToolExecutor(
                local = mainLocalTools,
                mcp = McpToolExecutor(mcpSnapshot),
            )
            toolExecutor = routingExecutor
            toolsBinding = runController.register(routingExecutor::close)
            timing.preparationFinished(skillContext.installedSkills.size)
            val historyTool = conversationId?.let { id ->
                ConversationHistoryTool {
                    val checkpoint = runBlocking { EtaDatabase.get(appContext).conversationDao().contextCheckpoint(id) }
                    val journal = AgentConversationCodec.decodeTranscript(checkpoint?.journalJson)
                        .ifEmpty { AgentConversationCodec.decodeTranscript(checkpoint?.historyJson) }
                    journal + session.transcript
                }
            }
            val runTools = JSONArray(mcpTools.toString()).also { tools ->
                if (historyTool != null) tools.put(AgentConversationToolCatalog.schema())
                if (characterMemoryTools != null && memoryEnabled) CharacterMemoryTools.appendSchemas(tools)
                tools.put(AgentUserQuestion.schema())
                tools.put(AgentTaskPlan.schema())
                AgentSubagentTools.schemas().forEach(tools::put)
            }
            fun emitRunEvent(event: AgentEvent) =
                acceptEvent(session, event, archivedEvents, entrySurfaceGuard, checkpointRecorder)
            // 回退链按本次 run 开始时的设置解析，备用模型同样受思考权限约束；改写回复不换模型。
            val fallbackPolicy = if (request.operation == AgentRuntimeWire.OP_REWRITE_REPLY) {
                ModelFallbackPolicy.NONE
            } else {
                val resolved = runBlocking {
                    // 电脑端任务限定了可用模型时，备用模型也只在其中选。
                    runCatching {
                        RuntimeConfigRepository.modelFallbackPolicy(request.config, EtaMcpModelAccess.fallbackScope(request))
                    }.getOrElse { throwable ->
                        AndroidAgentLogger.warnThrottled("agent_model_fallback_unavailable") {
                            "Model fallback settings unavailable: type=${throwable.safeLogType()}"
                        }
                        ModelFallbackPolicy.NONE
                    }
                }
                val permissions = currentPermissions()
                resolved.copy(configs = resolved.configs.map { AgentRuntimePolicy.constrain(it, permissions) })
            }
            // 计划只是进度声明：校验后推给浮层、通知与电脑端，不改变设备状态。
            fun updatePlan(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
                val plan = AgentTaskPlan.parse(call.argumentsJson).getOrElse { failure ->
                    return AgentModelClient.ToolResult(JSONObject()
                        .put("ok", false)
                        .put("code", "INVALID_PLAN")
                        .put("message", failure.message ?: "计划格式无效")
                        .toString())
                }
                emitRunEvent(AgentEvent.PlanUpdated(call.id, plan.toJson()))
                return AgentModelClient.ToolResult(JSONObject()
                    .put("ok", true)
                    .put("completed", plan.completedCount)
                    .put("total", plan.steps.size)
                    .toString())
            }
            // 用量账本按入口与 run 归类；同一线程上的压缩请求一并归入，子 Agent 在自己的线程上继承。
            val usageContext = ModelUsageContext(
                source = request.handoff?.source ?: AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
                runId = request.runId,
                sessionId = request.effectiveModelSessionId,
            )
            val subagents = AgentSubagentTools(
                manager = subagentManager,
                environment = SubagentEnvironment(
                    runId = request.runId,
                    conversationId = subagentConversation,
                    parentSessionId = request.effectiveModelSessionId,
                    config = request.config,
                    capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                    additionalTools = mcpTools,
                    disabledTools = disabledTools,
                    newExecutor = { ownerId ->
                        RoutingToolExecutor(local = newLocalTools(ownerId), mcp = McpToolExecutor(mcpSnapshot))
                    },
                    fallback = fallbackPolicy,
                    executionContext = mainLocalTools.terminalExecutionContext,
                    usageContext = usageContext,
                ),
            )
            if (receivesSubagentNotices) subagentManager.attachParent(subagentConversation, request.runId, runController)
            // 提问在工具内阻塞等待；问题与结论作为独立事件推给 App、胶囊和恢复日志。
            fun askUser(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
                val question = AgentUserQuestion.parse(call.argumentsJson)
                    ?: return AgentModelClient.ToolResult(JSONObject()
                        .put("ok", false)
                        .put("code", "INVALID_QUESTION")
                        .put("message", "问题为空或格式无效：至少提供一个问题，每题 2–4 个选项")
                        .toString())
                // 界面交接（AG-11 返工）：只在 App 里问；语音、自动任务、电脑端没人盯着屏幕，冷却期内也不再问。
                if (question.isGuiHandoff) {
                    GuiHandoffPolicy.skipReason(request.handoff?.source, subagentConversation)?.let { (status, message) ->
                        return AgentModelClient.ToolResult(JSONObject().put("status", status).put("message", message).toString())
                    }
                }
                val timeoutMs = if (question.isGuiHandoff) AgentUserQuestion.GUI_HANDOFF_TIMEOUT_MS else AgentUserQuestion.TIMEOUT_MS
                val questionId = "${request.runId}:${call.id}"
                acceptEvent(
                    session,
                    AgentEvent.UserQuestionAsked(questionId, call.id, question.toJson(), timeoutMs),
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
                val answer = runController.awaitUserAnswer(questionId, timeoutMs)
                acceptEvent(
                    session,
                    AgentEvent.UserQuestionResolved(questionId, answer.status),
                    archivedEvents,
                    entrySurfaceGuard,
                    checkpointRecorder,
                )
                if (question.isGuiHandoff && answer == AgentUserAnswer.TimedOut) {
                    GuiHandoffPolicy.startCooldown(subagentConversation)
                    return AgentModelClient.ToolResult(JSONObject().put("status", AgentUserAnswer.STATUS_TIMEOUT)
                        .put("message", "30 秒没有回复，由你直接操作；3 分钟内不再询问由谁操作").toString())
                }
                return AgentModelClient.ToolResult(answer.toToolResult(question))
            }
            val runToolExecutor = AgentModelClient.ToolExecutor { call ->
                // 执行器级工具不经 AgentLocalTools，停用校验在这里补上。
                if (call.name in EXECUTOR_TOOLS && call.name in disabledTools()) {
                    AgentModelClient.ToolResult(JSONObject()
                        .put("ok", false)
                        .put("code", "TOOL_DISABLED")
                        .put("message", "该工具已在工具设置中关闭，本次未执行")
                        .toString())
                } else if (call.name == AgentUserQuestion.TOOL_NAME) {
                    askUser(call)
                } else if (call.name == AgentTaskPlan.TOOL_NAME) {
                    updatePlan(call)
                } else if (call.name in AgentSubagentTools.TOOL_NAMES) {
                    subagents.execute(call)
                } else if (call.name == AgentConversationToolCatalog.READ_HISTORY && historyTool != null) {
                    historyTool.execute(call)
                } else if (call.name in CharacterMemoryTools.NAMES && characterMemoryTools != null) {
                    characterMemoryTools.execute(call)
                } else routingExecutor.execute(call).also { result ->
                    if (call.name == "memory_write") {
                        runCatching { JSONObject(result.content) }.getOrNull()
                            ?.takeIf { it.optBoolean("ok") }
                            ?.let {
                                AgentMemorySessionSnapshots.recordOwnWrite(
                                    request.effectiveModelSessionId,
                                    it.optString("revision"),
                                )
                            }
                    }
                }
            }
            val completedResponse = ModelUsageScope.run(usageContext) {
                AgentModelClient.complete(
                    config = request.config,
                    sessionId = request.effectiveModelSessionId,
                    operationId = request.runId,
                    initialUserMessageId = uiPayload?.promptMessageId(request.runId) ?: "user-${request.runId}",
                    initialSupplementIndex = uiPayload?.lastSupplementIndex ?: 0,
                    roleplayContext = roleplayContext,
                    rewriteReply = request.operation == AgentRuntimeWire.OP_REWRITE_REPLY,
                    compactOnly = request.operation == AgentRuntimeWire.OP_COMPACT,
                    onContextSnapshot = { snapshot ->
                        val committed = snapshot.copy(operationId = request.runId)
                        AgentRunCheckpointStore.saveContext(appContext, request.runId, committed)
                        session.updateContext(committed)
                    },
                    onTranscript = { transcript ->
                        AgentRunCheckpointStore.saveTranscript(appContext, request.runId, transcript)
                        session.updateTranscript(transcript)
                    },
                    capabilitiesProvider = { AgentToolCapabilities.capture(appContext) },
                    disabledToolsProvider = disabledTools,
                    prompt = request.prompt,
                    assistantScreenContext = request.assistantScreenContext,
                    toolExecutor = runToolExecutor,
                    images = request.images,
                    history = request.history,
                    runController = runController,
                    skillContext = skillContext,
                    memoryContext = memoryContext,
                    additionalTools = runTools,
                    fallback = fallbackPolicy,
                    executionContext = mainLocalTools.terminalExecutionContext,
                ) { event ->
                    timing.accept(event)
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }
            }
            response = completedResponse
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = true,
                content = completedResponse.content,
                reasoningContent = completedResponse.reasoningContent,
                transcript = completedResponse.transcript,
                contextSnapshot = completedResponse.contextSnapshot?.copy(operationId = request.runId),
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } catch (throwable: Throwable) {
            // 失败时历史是否完整落盘不确定，放弃固定的记忆快照；记忆未变时重新注入的内容逐字节相同。
            AgentMemorySessionSnapshots.forget(request.effectiveModelSessionId)
            cancelled = runController.isCancelled || throwable is AgentRunCancelledException
            // 主对话被停止时，它派出的、正在运行的子 Agent 一起停（已中断，之后可继续）。
            if (cancelled && receivesSubagentNotices) runCatching { subagentManager.stopRunning(subagentConversation) }
            val modelFailure = throwable as? AgentModelExecutionException
            val message = if (cancelled) {
                "已停止"
            } else {
                throwable.message ?: throwable.javaClass.simpleName
            }
            if (cancelled) {
                AndroidAgentLogger.info("Agent runtime stopped")
            } else {
                val requestFailure = modelFailure?.cause as? AgentModelFailure
                AndroidAgentLogger.error(
                    "Agent runtime failed: type=${throwable.safeLogType()}, " +
                        "model_code=${requestFailure?.code.orEmpty()}, " +
                        "cause_type=${requestFailure?.cause?.safeLogType().orEmpty()}"
                )
                val event = AgentEvent.RunFailed(message)
                runCatching {
                    acceptEvent(
                        session,
                        event,
                        archivedEvents,
                        entrySurfaceGuard,
                        checkpointRecorder,
                    )
                }.onFailure { checkpointFailure ->
                    AndroidAgentLogger.error(
                        "Agent runtime failure checkpoint failed: " +
                            "type=${checkpointFailure.safeLogType()}"
                    )
                    session.emit(event)
                }
            }
            AgentRuntimeWire.RunResult(
                runId = request.runId,
                ok = false,
                content = "",
                error = message,
                reasoningContent = modelFailure?.reasoningContent.orEmpty(),
                transcript = modelFailure?.transcript.orEmpty(),
                contextSnapshot = modelFailure?.contextSnapshot?.copy(operationId = request.runId) ?: session.contextSnapshot,
                operation = request.operation,
                rewriteTargetMessageId = request.rewriteTargetMessageId,
            )
        } finally {
            if (receivesSubagentNotices) runCatching { subagentManager.detachParent(subagentConversation, request.runId) }
            runCatching { toolsBinding?.close() }
            runCatching { toolExecutor?.close() }
            runCatching { guiRun?.close() }
            runCatching { browserBinding?.close() }
        }

        if (cancelled && session.isTerminal) {
            runCatching {
                persistArtifacts(snapshotRequest(request), result, archivedEvents.orEmpty())
            }.onFailure { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime cancelled result persistence failed: " +
                        "type=${throwable.safeLogType()}"
                )
            }
            return Outcome(
                result = result,
                entrySurfaceGuard = entrySurfaceGuard,
                shouldUpdateHost = true,
            )
        }

        val completedRequest = runCatching { snapshotRequest(request) }
            .getOrElse { throwable ->
                AndroidAgentLogger.error(
                    "Agent runtime request snapshot failed: type=${throwable.safeLogType()}"
                )
                request
            }
        val committed = session.complete(result) {
            runCatching { checkpointRecorder?.seal() }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime checkpoint seal failed: type=${throwable.safeLogType()}"
                    )
                }
            runCatching { persistArtifacts(completedRequest, result, archivedEvents.orEmpty()) }
                .onFailure { throwable ->
                    AndroidAgentLogger.error(
                        "Agent runtime artifact persistence failed: type=${throwable.safeLogType()}"
                    )
                }
        }
        return Outcome(
            result = result,
            entrySurfaceGuard = entrySurfaceGuard,
            completedRequest = completedRequest.takeIf { committed },
            response = response.takeIf { committed },
            shouldUpdateHost = committed,
        )
    }

    private fun acceptEvent(
        session: AgentRuntimeSession,
        event: AgentEvent,
        archivedEvents: MutableList<AgentEvent>?,
        entrySurfaceGuard: EntrySurfaceGuard?,
        checkpointRecorder: AgentRunCheckpointRecorder?,
    ) {
        checkpointRecorder?.accept(event)
        if (!session.emit(event)) return
        if (event !is AgentEvent.GuiControlChanged) archivedEvents?.add(event)
        if (event is AgentEvent.ModelRetryScheduled) {
            AndroidAgentLogger.warn("Agent runtime event: ${event.toLogLine()}")
        } else if (event !is AgentEvent.AssistantBlockDelta) {
            AndroidAgentLogger.debug { "Agent runtime event: ${event.toLogLine()}" }
        }
        runCatching { onAcceptedEvent(event, entrySurfaceGuard) }
            .onFailure { throwable ->
                AndroidAgentLogger.warnThrottled("runtime_event_projection_failed") {
                    "Agent runtime event projection failed: type=${throwable.safeLogType()}"
                }
            }
    }

    private companion object {
        val EXECUTOR_TOOLS = setOf(AgentUserQuestion.TOOL_NAME, AgentTaskPlan.TOOL_NAME) + AgentSubagentTools.TOOL_NAMES
    }
}
